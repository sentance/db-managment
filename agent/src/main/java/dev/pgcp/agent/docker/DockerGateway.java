package dev.pgcp.agent.docker;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.ContainerCredentials;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.agent.api.Libc;
import dev.pgcp.agent.config.AgentException;

/**
 * Thin wrapper over the Docker Engine API. Everything here is read-only except {@link #execFixed} and
 * {@link #removeManagedVolume}, both of which refuse to touch anything not labelled as pgcp-managed.
 */
@Component
public class DockerGateway implements Closeable {

    private static final List<String> REPORTED_LABEL_PREFIXES = List.of("pgcp.", "coolify.managed", "coolify.resourceName",
            "com.docker.compose.project");

    private final DockerClient client;

    public DockerGateway() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        ApacheDockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(16)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofMinutes(2))
                .build();
        this.client = DockerClientImpl.getInstance(config, http);
    }

    public String version() {
        return client.versionCmd().exec().getVersion();
    }

    public List<ContainerInfo> listContainers(boolean postgresOnly) {
        List<ContainerInfo> result = new ArrayList<>();
        for (Container c : client.listContainersCmd().withShowAll(true).exec()) {
            inspect(c.getId()).ifPresent(info -> {
                if (!postgresOnly || info.pgMajor() != null) {
                    result.add(info);
                }
            });
        }
        return result;
    }

    public Optional<ContainerInfo> inspect(String nameOrId) {
        InspectContainerResponse r;
        try {
            r = client.inspectContainerCmd(nameOrId).exec();
        } catch (NotFoundException e) {
            return Optional.empty();
        }
        Map<String, String> labels = r.getConfig().getLabels() == null ? Map.of() : r.getConfig().getLabels();
        Map<String, String> env = envMap(r.getConfig().getEnv());
        boolean managed = "true".equals(labels.get(AgentApi.LABEL_MANAGED));
        Integer pgMajor = parseMajor(env.get("PG_MAJOR"));
        String name = r.getName().startsWith("/") ? r.getName().substring(1) : r.getName();
        String health = r.getState().getHealth() == null ? null : r.getState().getHealth().getStatus();
        List<String> networks = r.getNetworkSettings().getNetworks() == null ? List.of()
                : List.copyOf(new TreeMap<>(r.getNetworkSettings().getNetworks()).keySet());
        Long mem = r.getHostConfig().getMemory();
        Libc libc = pgMajor == null ? null : managed ? Libc.GLIBC : detectLibc(r.getId());
        return Optional.of(new ContainerInfo(r.getId(), name, r.getConfig().getImage(), r.getState().getStatus(), health,
                describeState(r), managed, labels.get(AgentApi.LABEL_INSTANCE_ID), pgMajor, env.get("PG_VERSION"), libc,
                networks, reportedLabels(labels), mem == null || mem == 0 ? null : mem));
    }

    /**
     * Reads the credentials an official-image PostgreSQL container was started with. Only the three POSTGRES_*
     * variables are read, and only from containers that are PostgreSQL containers (PG_MAJOR set).
     */
    public ContainerCredentials postgresCredentials(String nameOrId) {
        InspectContainerResponse r;
        try {
            r = client.inspectContainerCmd(nameOrId).exec();
        } catch (NotFoundException e) {
            throw AgentException.notFound("container not found");
        }
        Map<String, String> env = envMap(r.getConfig().getEnv());
        if (parseMajor(env.get("PG_MAJOR")) == null) {
            throw AgentException.forbidden("not a PostgreSQL container");
        }
        String password = env.get("POSTGRES_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw AgentException.notFound("the container has no POSTGRES_PASSWORD in its environment; register it manually");
        }
        String user = env.getOrDefault("POSTGRES_USER", "postgres");
        String name = r.getName().startsWith("/") ? r.getName().substring(1) : r.getName();
        return new ContainerCredentials(name, user, password, env.getOrDefault("POSTGRES_DB", user));
    }

    public boolean imageExists(String tag) {
        try {
            client.inspectImageCmd(tag).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    public boolean networkExists(String name) {
        try {
            client.inspectNetworkCmd().withNetworkId(name).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    public List<String> logs(String container, int tail) {
        List<String> lines = new ArrayList<>();
        try (var callback = client.logContainerCmd(container)
                .withStdOut(true).withStdErr(true).withTimestamps(true).withTail(tail)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        String payload = new String(frame.getPayload()).stripTrailing();
                        synchronized (lines) {
                            lines.addAll(Arrays.asList(payload.split("\n")));
                        }
                    }
                })) {
            callback.awaitCompletion(15, TimeUnit.SECONDS);
        } catch (NotFoundException e) {
            throw AgentException.notFound("container not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new AgentException(AgentException.Kind.FAILED, "cannot read logs", e);
        }
        synchronized (lines) {
            return lines.size() > tail ? List.copyOf(lines.subList(lines.size() - tail, lines.size())) : List.copyOf(lines);
        }
    }

    /** Runs a fixed command inside a pgcp-managed container. */
    public int execFixed(String container, List<String> argv) {
        ContainerInfo info = inspect(container).orElseThrow(() -> AgentException.notFound("container not found"));
        if (!info.managed()) {
            throw AgentException.forbidden("refusing to exec in a container not managed by pgcp");
        }
        String execId = client.execCreateCmd(info.id()).withCmd(argv.toArray(String[]::new))
                .withAttachStdout(true).withAttachStderr(true).exec().getId();
        try (var callback = client.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>())) {
            callback.awaitCompletion(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new AgentException(AgentException.Kind.FAILED, "exec failed", e);
        }
        Long exit = client.inspectExecCmd(execId).exec().getExitCodeLong();
        return exit == null ? -1 : exit.intValue();
    }

    /** Removes a data volume, but only if its labels prove it belongs to the given pgcp instance. */
    public void removeManagedVolume(String volume, String instanceId) {
        Map<String, String> labels;
        try {
            labels = client.inspectVolumeCmd(volume).exec().getLabels();
        } catch (NotFoundException e) {
            return;
        }
        if (labels == null || !"true".equals(labels.get(AgentApi.LABEL_MANAGED))
                || !instanceId.equals(labels.get(AgentApi.LABEL_INSTANCE_ID))) {
            throw AgentException.forbidden("volume " + volume + " is not labelled as data of instance " + instanceId);
        }
        client.removeVolumeCmd(volume).exec();
    }

    private Libc detectLibc(String containerId) {
        if (pathExists(containerId, "/etc/alpine-release")) {
            return Libc.MUSL;
        }
        if (pathExists(containerId, "/etc/debian_version") || pathExists(containerId, "/etc/redhat-release")) {
            return Libc.GLIBC;
        }
        return Libc.UNKNOWN;
    }

    private boolean pathExists(String containerId, String path) {
        try (InputStream in = client.copyArchiveFromContainerCmd(containerId, path).exec()) {
            in.transferTo(OutputStreamSink.INSTANCE);
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String describeState(InspectContainerResponse r) {
        var s = r.getState();
        if (Boolean.TRUE.equals(s.getRunning())) {
            return "running since " + s.getStartedAt();
        }
        return s.getStatus() + (s.getExitCodeLong() == null ? "" : " (exit " + s.getExitCodeLong() + ")");
    }

    private static Map<String, String> envMap(String[] env) {
        Map<String, String> map = new TreeMap<>();
        if (env != null) {
            for (String e : env) {
                int i = e.indexOf('=');
                if (i > 0) {
                    map.put(e.substring(0, i), e.substring(i + 1));
                }
            }
        }
        return map;
    }

    private static Map<String, String> reportedLabels(Map<String, String> labels) {
        Map<String, String> out = new TreeMap<>();
        labels.forEach((k, v) -> {
            if (REPORTED_LABEL_PREFIXES.stream().anyMatch(k::startsWith)) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Integer parseMajor(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.contains(".") ? v.substring(0, v.indexOf('.')) : v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /** Discards bytes; used to fully consume archive streams. */
    private static final class OutputStreamSink extends java.io.OutputStream {
        static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }
}
