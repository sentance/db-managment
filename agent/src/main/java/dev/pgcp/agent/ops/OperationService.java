package dev.pgcp.agent.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.Operation;
import dev.pgcp.agent.api.OperationStatus;
import dev.pgcp.agent.api.OperationType;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs long operations asynchronously. Operations are idempotent by id (resubmitting returns the existing one),
 * at most one operation runs per target, and state is persisted so callers can still read results after a restart.
 */
@Service
public class OperationService {

    @FunctionalInterface
    public interface Work {
        Map<String, String> run(Progress progress) throws Exception;
    }

    @FunctionalInterface
    public interface Progress {
        void update(int percent, String message);
    }

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final Map<UUID, Operation> operations = new ConcurrentHashMap<>();
    private final Map<String, UUID> runningByTarget = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Path dir;
    private final JsonMapper json;

    public OperationService(AgentProperties props, JsonMapper json) throws IOException {
        this.dir = props.operationsDir();
        this.json = json;
        Files.createDirectories(dir);
        loadPersisted();
    }

    public synchronized Operation submit(UUID id, OperationType type, String target, Work work) {
        if (id == null) {
            throw AgentException.badRequest("operationId is required");
        }
        Operation existing = operations.get(id);
        if (existing != null) {
            if (existing.type() != type || !existing.target().equals(target)) {
                throw AgentException.conflict("operation id reused for a different operation");
            }
            return existing;
        }
        UUID running = runningByTarget.get(target);
        if (running != null) {
            throw AgentException.conflict("another operation is running for " + target + ": " + running);
        }
        Operation op = new Operation(id, type, target, OperationStatus.RUNNING, 0, "queued", null, Instant.now(), null, Map.of());
        store(op);
        runningByTarget.put(target, id);
        executor.submit(() -> execute(op, work));
        return op;
    }

    public Optional<Operation> get(UUID id) {
        return Optional.ofNullable(operations.get(id));
    }

    private void execute(Operation initial, Work work) {
        UUID id = initial.id();
        try {
            Map<String, String> result = work.run((percent, message) -> {
                Operation cur = operations.get(id);
                store(new Operation(id, cur.type(), cur.target(), OperationStatus.RUNNING, Math.max(0, Math.min(99, percent)),
                        message, null, cur.createdAt(), null, Map.of()));
            });
            Operation cur = operations.get(id);
            store(new Operation(id, cur.type(), cur.target(), OperationStatus.SUCCEEDED, 100, "done", null, cur.createdAt(),
                    Instant.now(), result == null ? Map.of() : Map.copyOf(result)));
        } catch (Exception e) {
            log.warn("operation {} {} failed: {}", initial.type(), initial.target(), e.getMessage());
            Operation cur = operations.get(id);
            String error = e instanceof AgentException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            store(new Operation(id, cur.type(), cur.target(), OperationStatus.FAILED, cur.progress(), cur.message(), error,
                    cur.createdAt(), Instant.now(), Map.of()));
        } finally {
            runningByTarget.remove(initial.target(), id);
        }
    }

    private void store(Operation op) {
        operations.put(op.id(), op);
        Path file = dir.resolve(op.id() + ".json");
        Path tmp = dir.resolve(op.id() + ".json.tmp");
        try {
            Files.write(tmp, json.writeValueAsBytes(op));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.error("cannot persist operation {}: {}", op.id(), e.toString());
        }
    }

    private void loadPersisted() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    Operation op = json.readValue(f.toFile(), Operation.class);
                    if (op.status() == OperationStatus.RUNNING) {
                        op = new Operation(op.id(), op.type(), op.target(), OperationStatus.FAILED, op.progress(), op.message(),
                                "interrupted: agent restarted while the operation was running", op.createdAt(), Instant.now(),
                                Map.of());
                        store(op);
                    }
                    operations.put(op.id(), op);
                } catch (RuntimeException e) {
                    log.warn("skipping unreadable operation file {}: {}", f, e.toString());
                }
            });
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeOld() {
        Instant cutoff = Instant.now().minus(RETENTION);
        operations.values().removeIf(op -> {
            boolean old = op.finishedAt() != null && op.finishedAt().isBefore(cutoff);
            if (old) {
                try {
                    Files.deleteIfExists(dir.resolve(op.id() + ".json"));
                } catch (IOException e) {
                    log.debug("cannot delete {}: {}", op.id(), e.toString());
                }
            }
            return old;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
