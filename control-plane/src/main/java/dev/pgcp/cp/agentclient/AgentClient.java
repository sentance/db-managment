package dev.pgcp.cp.agentclient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.ApiError;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.agent.api.CreateInstanceRequest;
import dev.pgcp.agent.api.DeleteInstanceRequest;
import dev.pgcp.agent.api.HostFacts;
import dev.pgcp.agent.api.HostMetrics;
import dev.pgcp.agent.api.InstanceLifecycleRequest;
import dev.pgcp.agent.api.LogLines;
import dev.pgcp.agent.api.Operation;
import dev.pgcp.agent.api.OperationStatus;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.config.CpProperties;
import dev.pgcp.cp.jobs.JobContext;
import tools.jackson.databind.json.JsonMapper;

/** Client of the local management agent over mutual TLS. */
@Component
public class AgentClient {

    private static final Duration POLL = Duration.ofSeconds(2);
    private static final Duration MAX_WAIT = Duration.ofMinutes(30);

    private final RestClient rest;
    private final JsonMapper json;

    public AgentClient(CpProperties props, SslBundles sslBundles, JsonMapper json) {
        this.json = json;
        HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (props.agent().tls()) {
            http.sslContext(sslBundles.getBundle("agent-client").createSslContext());
        }
        var factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(Duration.ofMinutes(2));
        this.rest = RestClient.builder()
                .baseUrl(props.agent().url() + AgentApi.VERSION_PREFIX)
                .requestFactory(factory)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw toApiException(res.getStatusCode(), body);
                })
                .build();
    }

    public HostFacts facts() {
        return call(() -> rest.get().uri("/host/facts").retrieve().body(HostFacts.class));
    }

    public HostMetrics metrics() {
        return call(() -> rest.get().uri("/host/metrics").retrieve().body(HostMetrics.class));
    }

    public List<ContainerInfo> postgresContainers() {
        return call(() -> rest.get().uri("/containers?postgresOnly=true").retrieve()
                .body(new ParameterizedTypeReference<List<ContainerInfo>>() {
                }));
    }

    public ContainerInfo managedInstance(String name) {
        return call(() -> rest.get().uri("/instances/{name}", name).retrieve().body(ContainerInfo.class));
    }

    public LogLines logs(String container, int tail) {
        return call(() -> rest.get().uri("/containers/{name}/logs?tail={tail}", container, tail).retrieve().body(LogLines.class));
    }

    public String instancesCa() {
        return call(() -> rest.get().uri("/tls/instances-ca").retrieve().body(String.class));
    }

    public Operation createInstance(CreateInstanceRequest req) {
        return call(() -> rest.post().uri("/instances").body(req).retrieve().body(Operation.class));
    }

    public Operation lifecycle(String name, InstanceLifecycleRequest req) {
        return call(() -> rest.post().uri("/instances/{name}/lifecycle", name).body(req).retrieve().body(Operation.class));
    }

    public Operation deleteInstance(String name, DeleteInstanceRequest req) {
        return call(() -> rest.post().uri("/instances/{name}/delete", name).body(req).retrieve().body(Operation.class));
    }

    public Operation operation(UUID id) {
        return call(() -> rest.get().uri("/operations/{id}", id).retrieve().body(Operation.class));
    }

    /** Polls an agent operation to completion, projecting its progress onto [from, to] of the job's progress. */
    public Operation await(Operation op, JobContext ctx, int from, int to) throws InterruptedException {
        Instant deadline = Instant.now().plus(MAX_WAIT);
        String lastMessage = null;
        while (op.status() == OperationStatus.RUNNING) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("agent operation " + op.id() + " did not finish within " + MAX_WAIT);
            }
            Thread.sleep(POLL);
            op = operation(op.id());
            if (op.message() != null && !op.message().equals(lastMessage)) {
                lastMessage = op.message();
                ctx.progress(from + (to - from) * op.progress() / 100, op.message());
            }
        }
        if (op.status() == OperationStatus.FAILED) {
            throw new IllegalStateException("agent: " + op.error());
        }
        return op;
    }

    /** Deterministic operation id per job step, so a retried job re-attaches to the agent operation it started. */
    public static UUID operationId(UUID jobId, String step) {
        return UUID.nameUUIDFromBytes((jobId + ":" + step).getBytes(StandardCharsets.UTF_8));
    }

    private <T> T call(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (ResourceAccessException e) {
            throw ApiException.unavailable("management agent is unreachable: " + e.getMostSpecificCause().getMessage());
        }
    }

    private ApiException toApiException(HttpStatusCode status, String body) {
        String message = body;
        try {
            ApiError err = json.readValue(body, ApiError.class);
            if (err.message() != null) {
                message = err.message();
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        HttpStatus mapped = switch (status.value()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new ApiException(mapped, "AGENT_" + mapped.name(), "agent: " + message);
    }
}
