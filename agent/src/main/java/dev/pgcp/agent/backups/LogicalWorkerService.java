package dev.pgcp.agent.backups;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.CommandRunner;
import dev.pgcp.agent.docker.DockerGateway;
import dev.pgcp.agent.instances.ImageService;
import dev.pgcp.agent.ops.OperationService;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/** Named workers survive panel/agent restarts. State files contain no credentials. */
@Service
public class LogicalWorkerService {
    record Descriptor(Operation operation, String fingerprint, String worker, int pgMajor, Instant deadline) { }
    private final AgentProperties props;
    private final DockerGateway docker;
    private final CommandRunner commands;
    private final OperationService locks;
    private final JsonMapper json;
    private final boolean allowHttp;
    private final String storageNetwork;
    private final Path root;
    private final Map<UUID, Descriptor> states = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> launching = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object imageLock = new Object();

    public LogicalWorkerService(AgentProperties props, DockerGateway docker, CommandRunner commands,
            OperationService locks, JsonMapper json,
            @Value("${pgcp.backups.allow-http-storage:false}") boolean allowHttp,
            @Value("${pgcp.backups.storage-network:bridge}") String storageNetwork) throws IOException {
        this.props = props; this.docker = docker; this.commands = commands; this.locks = locks; this.json = json;
        this.allowHttp = allowHttp; this.storageNetwork = storageNetwork; this.root = props.dataDir().resolve("logical-operations");
        Files.createDirectories(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path state = dir.resolve("state.json");
                if (!Files.exists(state)) continue;
                Descriptor descriptor = json.readValue(state.toFile(), Descriptor.class);
                states.put(descriptor.operation().id(), descriptor);
                if (descriptor.operation().status() == OperationStatus.RUNNING) {
                    locks.reserveExternal(descriptor.operation().id(), descriptor.operation().target());
                    launchAsync(descriptor.operation().id());
                } else cleanup(descriptor);
            }
        }
    }

    public RecoveryKey recoveryKey() {
        String image = ensureImage(18);
        var result = commands.run(List.of(props.dockerCommand(), "run", "--rm", "--network", "none", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--entrypoint", "age-keygen", image), null, Duration.ofSeconds(30));
        if (result.exitCode() != 0) throw failure("recovery key generation failed");
        String identity = result.output().lines().filter(s -> s.startsWith("AGE-SECRET-KEY-1")).findFirst().orElseThrow(() -> failure("invalid recovery key"));
        String recipient = result.output().lines().filter(s -> s.startsWith("# public key: ") || s.startsWith("Public key: "))
                .map(s -> s.substring(s.indexOf(": ") + 2)).findFirst().orElseThrow(() -> failure("invalid public recipient"));
        return new RecoveryKey(recipient, identity + "\n");
    }

    public synchronized Operation start(LogicalBackupRequest request) throws Exception {
        try { request.validate(allowHttp); } catch (IllegalArgumentException e) { throw AgentException.badRequest(e.getMessage()); }
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
        Descriptor existing = states.get(request.operationId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw AgentException.conflict("operation ID reused for a different request");
            return get(request.operationId());
        }
        if (states.values().stream().anyMatch(d -> d.operation().status() == OperationStatus.RUNNING)) {
            throw AgentException.conflict("another backup worker is active; retry after it finishes");
        }
        String target = request.action() == LogicalBackupRequest.Action.EXPIRE ? "artifact:"+request.artifactKey()
                : request.action() == LogicalBackupRequest.Action.TEST_STORAGE ? "storage:" + request.instanceId()
                : "instance:" + target(request).name().replaceFirst("^pgcp-inst-", "");
        locks.reserveExternal(request.operationId(), target);
        try {
            Path dir = root.resolve(request.operationId().toString());
            Files.createDirectories(dir.resolve("work"));
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(dir.resolve("work"), PosixFilePermissions.fromString("rwx------"));
            Map<String, Object> config = new java.util.LinkedHashMap<>(json.convertValue(request, new tools.jackson.core.type.TypeReference<Map<String,Object>>() {}));
            config.put("allowHttpStorage", allowHttp);
            Path secret = dir.resolve("request.json");
            Files.write(secret, json.writeValueAsBytes(config));
            Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
            OperationType type = switch (request.action()) {
                case BACKUP -> OperationType.LOGICAL_BACKUP;
                case RESTORE -> OperationType.LOGICAL_RESTORE;
                case TEST_STORAGE -> OperationType.TEST_STORAGE;
                case EXPIRE -> OperationType.EXPIRE_BACKUP;
            };
            var op = new Operation(request.operationId(), type, target, OperationStatus.RUNNING, 0, "preparing worker", null,
                    Instant.now(), null, Map.of());
            store(new Descriptor(op, fingerprint, "pgcp-worker-" + request.operationId(), request.pgMajor(), Instant.now().plusSeconds(3900)));
            launchAsync(request.operationId());
            return op;
        } catch (Exception e) {
            locks.releaseExternal(request.operationId(), target);
            throw e;
        }
    }

    private ContainerInfo target(LogicalBackupRequest request) {
        ContainerInfo info = docker.inspect(request.containerName()).orElseThrow(() -> AgentException.conflict("source/target container is missing"));
        if (!info.id().equals(request.containerId()) || info.pgMajor() == null || info.pgMajor() != request.pgMajor() || !"running".equals(info.state())) {
            throw AgentException.conflict("source/target identity, version or state changed");
        }
        if (info.labels().containsKey("pgcp.logical-operation")) throw AgentException.forbidden("a backup worker is not a database source");
        if (request.action() == LogicalBackupRequest.Action.RESTORE && (!info.managed()
                || !request.instanceId().toString().equals(info.instanceId())
                || !request.operationId().toString().equals(info.labels().get("pgcp.restore-operation"))
                || !info.networks().equals(List.of("pgcp-restore-" + request.operationId())))) {
            throw AgentException.forbidden("restore requires a fresh isolated destination owned by this operation");
        }
        if (request.action() == LogicalBackupRequest.Action.BACKUP && info.managed()
                && !request.instanceId().toString().equals(info.instanceId())) {
            throw AgentException.forbidden("source instance identity does not match");
        }
        return info;
    }

    private void launchAsync(UUID id) {
        if (!launching.add(id)) return;
        executor.submit(() -> {
            try { launch(id); }
            catch (Exception e) { if (!Thread.currentThread().isInterrupted()) finish(id, false, Map.of(), "worker preparation failed; check local resources and source identity"); }
            finally { launching.remove(id); }
        });
    }

    private void launch(UUID id) throws Exception {
        Descriptor d = states.get(id);
        var existing = docker.inspect(d.worker());
        if (existing.isPresent()) {
            requireOwned(existing.get(), id);
            if ("created".equals(existing.get().state())) {
                LogicalBackupRequest req = readRequest(id);
                connectSource(req, existing.get());
                safeRun(List.of(props.dockerCommand(), "start", d.worker()));
            }
            return;
        }
        Path dir = root.resolve(id.toString());
        LogicalBackupRequest req = readRequest(id);
        if (req.action() == LogicalBackupRequest.Action.BACKUP || req.action() == LogicalBackupRequest.Action.RESTORE) target(req);
        if (req.action() != LogicalBackupRequest.Action.EXPIRE && Files.getFileStore(root).getUsableSpace() < 5L * 1024 * 1024 * 1024) throw failure("insufficient staging disk");
        String image = ensureImage(req.pgMajor());
        List<String> args = new ArrayList<>(List.of(props.dockerCommand(), "create", "--name", d.worker(),
                "--user", Files.getAttribute(dir,"unix:uid") + ":" + Files.getAttribute(dir,"unix:gid"),
                "--label", "pgcp.logical-operation=" + id, "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--memory", "512m", "--cpus", "1", "--pids-limit", "128", "--network", storageNetwork, "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m,mode=1777",
                "--mount", "type=bind,src=" + dir.resolve("request.json") + ",dst=/run/pgcp/request.json,readonly",
                "--mount", "type=bind,src=" + dir.resolve("work") + ",dst=/work", image));
        safeRun(args);
        connectSource(req, docker.inspect(d.worker()).orElseThrow(() -> failure("worker disappeared during preparation")));
        safeRun(List.of(props.dockerCommand(), "start", d.worker()));
    }

    private LogicalBackupRequest readRequest(UUID id) {
        return json.readerFor(LogicalBackupRequest.class)
                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(root.resolve(id.toString()).resolve("request.json").toFile());
    }
    private void connectSource(LogicalBackupRequest req, ContainerInfo worker) {
        if (req.action() == LogicalBackupRequest.Action.BACKUP || req.action() == LogicalBackupRequest.Action.RESTORE) {
            ContainerInfo source = target(req);
            // Backup sources choose an existing network; restore targets have exactly their dedicated internal network.
            String network = source.networks().stream().filter(n -> !n.equals("host") && !n.equals("none") && !n.equals("bridge"))
                    .findFirst().orElseThrow(() -> failure("a user-defined source network is required"));
            if (!worker.networks().contains(network)) safeRun(List.of(props.dockerCommand(), "network", "connect", network, worker.name()));
        }
    }

    public synchronized Operation get(UUID id) {
        Descriptor d = states.get(id);
        if (d == null) throw AgentException.notFound("logical operation not found");
        if (d.operation().status() != OperationStatus.RUNNING || launching.contains(id)) return d.operation();
        var worker = docker.inspect(d.worker());
        if (worker.isEmpty()) { finish(id, false, Map.of(), "worker disappeared; operation was not retried automatically"); }
        else {
            requireOwned(worker.get(), id);
            if (Instant.now().isAfter(d.deadline()) && !List.of("exited", "dead").contains(worker.get().state())) {
                if ("running".equals(worker.get().state())) safeRun(List.of(props.dockerCommand(), "kill", d.worker()));
                finish(id, false, Map.of(), "worker exceeded its deadline");
            } else if ("created".equals(worker.get().state())) {
                launchAsync(id); // Recover a crash between create, network attachment and start.
            } else if ("exited".equals(worker.get().state()) || "dead".equals(worker.get().state())) {
                Path result = root.resolve(id.toString()).resolve("work/result.json");
                try {
                    if (Files.size(result) > 2 * 1024 * 1024) throw new IOException("oversized result");
                    var node = json.readTree(Files.readString(result));
                    Map<String, String> values = new java.util.LinkedHashMap<>();
                    if (node.path("ok").asBoolean(false)) {
                        node.path("result").properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
                        finish(id, true, values, null);
                    } else finish(id, false, Map.of(), "worker failed; check source permissions, extension compatibility, recovery key and storage access");
                } catch (IOException | RuntimeException e) { finish(id, false, Map.of(), "worker exited without a valid result"); }
            } else {
                try {
                    Path path = root.resolve(id.toString()).resolve("work/progress.json");
                    if (Files.exists(path)) {
                        var progress = json.readTree(Files.readString(path));
                        var op = d.operation();
                        // Worker messages are fixed stages; never forward its raw diagnostics.
                        store(new Descriptor(new Operation(id, op.type(), op.target(), op.status(),
                                Math.min(99, Math.max(0, progress.path("percent").asInt())), "backup worker running", null,
                                op.createdAt(), null, Map.of()), d.fingerprint(), d.worker(), d.pgMajor(), d.deadline()));
                    }
                } catch (IOException ignored) { }
            }
        }
        return states.get(id).operation();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void reconcile() {
        for (Descriptor descriptor : List.copyOf(states.values())) {
            if (descriptor.operation().status() == OperationStatus.RUNNING) {
                try { get(descriptor.operation().id()); } catch (RuntimeException ignored) { /* retry after transient Docker outage */ }
            } else if (Files.exists(root.resolve(descriptor.operation().id().toString()).resolve("request.json"))) {
                try { cleanup(descriptor); } catch (RuntimeException ignored) { /* retry terminal credential cleanup */ }
            }
        }
    }

    private synchronized void finish(UUID id, boolean ok, Map<String,String> result, String error) {
        Descriptor d = states.get(id);
        if (d.operation().status() != OperationStatus.RUNNING) return;
        docker.inspect(d.worker()).ifPresent(info -> {
            requireOwned(info, id);
            if ("running".equals(info.state())) safeRun(List.of(props.dockerCommand(), "kill", d.worker()));
        });
        var op = d.operation();
        store(new Descriptor(new Operation(id, op.type(), op.target(), ok ? OperationStatus.SUCCEEDED : OperationStatus.FAILED,
                ok ? 100 : op.progress(), ok ? "completed" : "failed", error, op.createdAt(), Instant.now(), result),
                d.fingerprint(), d.worker(), d.pgMajor(), d.deadline()));
        locks.releaseExternal(id, op.target());
        cleanup(states.get(id));
    }

    private void cleanup(Descriptor descriptor) {
        UUID id = descriptor.operation().id();
        try { Files.deleteIfExists(root.resolve(id.toString()).resolve("request.json")); } catch (IOException e) { throw failure("cannot remove worker credentials"); }
        docker.inspect(descriptor.worker()).ifPresent(info -> { requireOwned(info, id); safeRun(List.of(props.dockerCommand(), "rm", "--volumes", descriptor.worker())); });
        try (var files = Files.list(root.resolve(id.toString()).resolve("work"))) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        } catch (IOException ignored) { /* ciphertext/diagnostics only; credentials already removed */ }
    }

    private void requireOwned(ContainerInfo info, UUID id) {
        // DockerGateway's reported-label allowlist includes pgcp.*.
        if (!id.toString().equals(info.labels().get("pgcp.logical-operation"))) throw AgentException.forbidden("worker container is not owned by this operation");
    }

    private String ensureImage(int major) {
        synchronized (imageLock) {
        Path context = props.imageContextDir().getParent().resolve("backup-worker");
        String image = "pgcp-backup-worker:" + major + "-" + ImageService.hashContext(context);
        if (!docker.imageExists(image)) safeRun(List.of(props.dockerCommand(), "build", "--build-arg", "PG_MAJOR=" + major, "-t", image, context.toString()), Duration.ofMinutes(20));
        return image;
        }
    }
    private void safeRun(List<String> args) { safeRun(args, Duration.ofMinutes(1)); }
    private void safeRun(List<String> args, Duration timeout) {
        if (commands.run(args, null, timeout).exitCode() != 0) throw failure("worker Docker operation failed");
    }
    private void store(Descriptor descriptor) {
        try {
            Path file = root.resolve(descriptor.operation().id().toString()).resolve("state.json");
            Path tmp = file.resolveSibling("state.tmp");
            Files.write(tmp, json.writeValueAsBytes(descriptor));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            states.put(descriptor.operation().id(), descriptor);
        } catch (IOException e) { throw failure("cannot persist worker state"); }
    }
    private static AgentException failure(String message) { return new AgentException(AgentException.Kind.FAILED, message); }
    @PreDestroy public void shutdown() { executor.shutdownNow(); }
}
