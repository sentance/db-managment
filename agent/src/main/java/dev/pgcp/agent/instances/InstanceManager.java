package dev.pgcp.agent.instances;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.agent.api.InstanceSpec;
import dev.pgcp.agent.api.LifecycleAction;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.CommandRunner;
import dev.pgcp.agent.docker.DockerGateway;
import dev.pgcp.agent.host.HostService;
import dev.pgcp.agent.ops.OperationService.Progress;
import dev.pgcp.agent.tls.InstanceCa;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lifecycle of managed PostgreSQL instances. Each instance is a compose project in its own directory so it can be
 * operated by hand if the agent is unavailable. Every mutating call first proves that the target is pgcp-managed
 * and belongs to the expected instance id.
 */
@Service
public class InstanceManager {

    record InstanceRecord(UUID instanceId, String name, int pgMajor, Instant createdAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(InstanceManager.class);

    private final AgentProperties props;
    private final DockerGateway docker;
    private final CommandRunner commands;
    private final HostService host;
    private final InstanceCa ca;
    private final ImageService images;
    private final JsonMapper json;

    public InstanceManager(AgentProperties props, DockerGateway docker, CommandRunner commands, HostService host,
            InstanceCa ca, ImageService images, JsonMapper json) {
        this.props = props;
        this.docker = docker;
        this.commands = commands;
        this.host = host;
        this.ca = ca;
        this.images = images;
        this.json = json;
    }

    public Map<String, String> create(InstanceSpec spec, Progress progress) throws IOException {
        InstanceFiles.validate(spec);
        String container = AgentApi.containerName(spec.name());
        Path dir = props.instancesDir().resolve(spec.name());
        boolean resuming = Files.exists(dir.resolve("instance.json"));

        progress.update(5, "checking existing state");
        if (resuming) {
            InstanceRecord rec = readRecord(dir);
            if (!rec.instanceId().equals(spec.instanceId())) {
                throw AgentException.conflict("instance name " + spec.name() + " is used by another instance");
            }
        }
        docker.inspect(container).ifPresent(c -> {
            if (!c.managed() || !spec.instanceId().toString().equals(c.instanceId())) {
                throw AgentException.conflict("container " + container + " already exists and is not this instance");
            }
        });
        if (!docker.networkExists(AgentApi.INTERNAL_NETWORK)) {
            throw new AgentException(AgentException.Kind.FAILED, "network " + AgentApi.INTERNAL_NETWORK + " does not exist");
        }
        for (String n : spec.extraNetworks() == null ? List.<String>of() : spec.extraNetworks()) {
            if (!docker.networkExists(n)) {
                throw AgentException.badRequest("network does not exist: " + n);
            }
        }

        if (!resuming) {
            progress.update(10, "checking host resources");
            checkResources(spec);
        }

        String image = images.tagFor(spec.pgMajor());
        progress.update(15, "ensuring image " + image);
        images.ensure(spec.pgMajor(), (p, m) -> progress.update(15 + p / 3, m));

        progress.update(50, "writing configuration");
        writeFiles(dir, spec, image, resuming);

        progress.update(60, "starting container");
        Path composeFile = dir.resolve("compose.yml");
        CommandRunner.Result up = commands.run(composeArgs(spec.name(), composeFile, "up", "--detach", "--wait",
                "--wait-timeout", String.valueOf(props.healthTimeout().toSeconds())), dir, props.healthTimeout().plusMinutes(2));
        if (up.exitCode() != 0) {
            List<String> logs = safeLogs(container);
            throw new AgentException(AgentException.Kind.FAILED, "instance did not become healthy: "
                    + lastLines(up.output(), 10) + "\n--- container log ---\n" + String.join("\n", logs));
        }

        progress.update(90, "removing initdb password from disk");
        scrubSuperuserPassword(dir, container);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("containerName", container);
        result.put("internalHost", container);
        result.put("internalPort", "5432");
        result.put("image", image);
        result.put("libc", "GLIBC");
        if (spec.hostPort() != null) {
            result.put("hostPort", String.valueOf(spec.hostPort()));
        }
        return result;
    }

    public Map<String, String> lifecycle(String name, UUID instanceId, LifecycleAction action, Progress progress) {
        Path dir = requireManaged(name, instanceId);
        progress.update(10, action.name().toLowerCase() + " " + name);
        String verb = switch (action) {
            case START -> "start";
            case STOP -> "stop";
            case RESTART -> "restart";
        };
        commands.runChecked(composeArgs(name, dir.resolve("compose.yml"), verb), dir, Duration.ofMinutes(5));
        if (action != LifecycleAction.STOP) {
            waitHealthy(AgentApi.containerName(name));
        }
        ContainerInfo info = docker.inspect(AgentApi.containerName(name)).orElseThrow();
        return Map.of("state", info.state(), "health", String.valueOf(info.health()));
    }

    public Map<String, String> delete(String name, UUID instanceId, boolean deleteVolume, Progress progress) throws IOException {
        Path dir = requireManaged(name, instanceId);
        progress.update(10, "stopping and removing container");
        commands.runChecked(composeArgs(name, dir.resolve("compose.yml"), "down", "--remove-orphans"), dir,
                Duration.ofMinutes(5));
        if (deleteVolume) {
            progress.update(60, "removing data volume");
            docker.removeManagedVolume(AgentApi.volumeName(name), instanceId.toString());
        }
        Path archive = props.instancesDir().resolve(".deleted").resolve(name + "-" + Instant.now().getEpochSecond());
        Files.createDirectories(archive.getParent());
        Files.move(dir, archive);
        log.info("instance {} ({}) deleted, volume {}", name, instanceId, deleteVolume ? "removed" : "kept");
        return Map.of("volume", deleteVolume ? "removed" : "kept:" + AgentApi.volumeName(name), "configArchive",
                archive.toString());
    }

    public ContainerInfo describe(String name) {
        try {
            AgentApi.requireValidInstanceName(name);
        } catch (IllegalArgumentException e) {
            throw AgentException.badRequest(e.getMessage());
        }
        return docker.inspect(AgentApi.containerName(name)).filter(ContainerInfo::managed)
                .orElseThrow(() -> AgentException.notFound("managed instance not found: " + name));
    }

    /** Verifies the directory, container labels and instance id all agree before anything is changed. */
    Path requireManaged(String name, UUID instanceId) {
        try {
            AgentApi.requireValidInstanceName(name);
        } catch (IllegalArgumentException e) {
            throw AgentException.badRequest(e.getMessage());
        }
        if (instanceId == null) {
            throw AgentException.badRequest("instanceId is required");
        }
        Path dir = props.instancesDir().resolve(name);
        if (!Files.exists(dir.resolve("instance.json"))) {
            throw AgentException.notFound("managed instance not found: " + name);
        }
        if (!readRecord(dir).instanceId().equals(instanceId)) {
            throw AgentException.forbidden("instance id does not match " + name);
        }
        docker.inspect(AgentApi.containerName(name)).ifPresent(c -> {
            if (!c.managed() || !instanceId.toString().equals(c.instanceId())) {
                throw AgentException.forbidden("container " + c.name() + " is not managed by pgcp for this instance");
            }
        });
        return dir;
    }

    private void checkResources(InstanceSpec spec) {
        long needed = (spec.memoryMb() + (long) props.memHeadroomMb()) * 1024 * 1024;
        long available = host.memAvailableBytes();
        if (available < needed) {
            throw new AgentException(AgentException.Kind.INSUFFICIENT_RESOURCES, "not enough memory: "
                    + available / (1024 * 1024) + " MB available, " + needed / (1024 * 1024) + " MB needed incl. headroom");
        }
        long free = host.freeBytes(props.dataDir());
        if (free >= 0 && free < props.minFreeDiskBytes()) {
            throw new AgentException(AgentException.Kind.INSUFFICIENT_RESOURCES,
                    "not enough free disk: " + free / (1024 * 1024) + " MB free, minimum " + props.minFreeDiskBytes() / (1024 * 1024) + " MB");
        }
    }

    private void writeFiles(Path dir, InstanceSpec spec, String image, boolean resuming) throws IOException {
        Path config = dir.resolve("config");
        Path tls = config.resolve("tls");
        Files.createDirectories(tls);
        if (!resuming) {
            Files.write(dir.resolve("instance.json"),
                    json.writeValueAsBytes(new InstanceRecord(spec.instanceId(), spec.name(), spec.pgMajor(), Instant.now())));
        }
        Files.writeString(config.resolve("pg_hba.conf"), InstanceFiles.pgHba(spec), StandardCharsets.UTF_8);
        Files.writeString(config.resolve("pg_ident.conf"), InstanceFiles.pgIdent(), StandardCharsets.UTF_8);
        if (!Files.exists(tls.resolve("server.key"))) {
            List<String> names = new ArrayList<>();
            names.add(AgentApi.containerName(spec.name()));
            names.add("localhost");
            if (spec.tlsHostnames() != null) {
                names.addAll(spec.tlsHostnames());
            }
            var cert = ca.issueServerCertificate(AgentApi.containerName(spec.name()), names);
            Files.writeString(tls.resolve("server.crt"), cert.certificatePem(), StandardCharsets.US_ASCII);
            InstanceCa.writePrivate(tls.resolve("server.key"), cert.privateKeyPem());
        }
        InstanceCa.writePrivate(config.resolve("superuser-password"), spec.superuserPassword() + "\n");
        Files.writeString(dir.resolve("compose.yml"), InstanceFiles.compose(spec, image), StandardCharsets.UTF_8);
    }

    private void scrubSuperuserPassword(Path dir, String container) throws IOException {
        InstanceCa.writePrivate(dir.resolve("config").resolve("superuser-password"), InstanceFiles.PASSWORD_PLACEHOLDER);
        int exit = docker.execFixed(container, List.of("install", "-o", "postgres", "-g", "postgres", "-m", "0600",
                InstanceFiles.SRC_DIR + "/superuser-password", InstanceFiles.RUNTIME_DIR + "/superuser-password"));
        if (exit != 0) {
            throw new AgentException(AgentException.Kind.FAILED, "could not scrub initdb password inside container (exit " + exit + ")");
        }
    }

    private void waitHealthy(String container) {
        long deadline = System.nanoTime() + props.healthTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            var info = docker.inspect(container).orElseThrow(() -> AgentException.notFound("container disappeared"));
            if ("healthy".equals(info.health())) {
                return;
            }
            if ("exited".equals(info.state()) || "dead".equals(info.state())) {
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AgentException(AgentException.Kind.FAILED,
                "instance is not healthy:\n" + String.join("\n", safeLogs(container)));
    }

    private List<String> composeArgs(String name, Path composeFile, String... args) {
        List<String> argv = new ArrayList<>(List.of(props.dockerCommand(), "compose", "--project-name",
                AgentApi.containerName(name), "--file", composeFile.toString()));
        argv.addAll(List.of(args));
        return argv;
    }

    private InstanceRecord readRecord(Path dir) {
        return json.readValue(dir.resolve("instance.json").toFile(), InstanceRecord.class);
    }

    private List<String> safeLogs(String container) {
        try {
            return docker.logs(container, 30);
        } catch (RuntimeException e) {
            return List.of("(logs unavailable: " + e.getMessage() + ")");
        }
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.strip().split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - n), lines.length));
    }
}
