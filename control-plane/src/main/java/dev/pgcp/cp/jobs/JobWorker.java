package dev.pgcp.cp.jobs;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;
import jakarta.annotation.PreDestroy;

/** Polls the job table and runs handlers on virtual threads. */
@Component
public class JobWorker {

    static final int MAX_CONCURRENT = 4;
    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobService jobs;
    private final Json json;
    private final AuditService audit;
    private final Map<String, JobHandler> handlers;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean ready;

    public JobWorker(JobService jobs, Json json, AuditService audit, List<JobHandler> handlers) {
        this.jobs = jobs;
        this.json = json;
        this.audit = audit;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
    }

    /** Jobs left RUNNING by a crash are re-queued (handlers are idempotent) until the attempt limit is reached. */
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (JobService.Claimed j : jobs.running()) {
            if (j.attempts() < MAX_ATTEMPTS) {
                jobs.event(j.id(), "WARN", "control plane restarted while the job was running; retrying");
                jobs.requeue(j.id());
            } else {
                jobs.fail(j.id(), "interrupted by control-plane restart too many times");
            }
        }
        ready = true;
    }

    @Scheduled(fixedDelayString = "${pgcp.jobs.poll-interval:500ms}")
    public void poll() {
        while (ready && running.get() < MAX_CONCURRENT) {
            var claimed = jobs.claimNext();
            if (claimed.isEmpty()) {
                return;
            }
            running.incrementAndGet();
            executor.submit(() -> {
                try {
                    execute(claimed.get());
                } finally {
                    running.decrementAndGet();
                }
            });
        }
    }

    void execute(JobService.Claimed job) {
        JobHandler handler = handlers.get(job.type());
        Ctx ctx = new Ctx(job.id(), job.params());
        if (handler == null) {
            jobs.fail(job.id(), "no handler for job type " + job.type());
            return;
        }
        try {
            ctx.info("started (attempt " + job.attempts() + ")");
            handler.run(ctx);
            jobs.succeed(job.id());
            ctx.info("finished successfully");
            audit.success("job.finish", "job", job.id(), Map.of("type", job.type()));
        } catch (Exception e) {
            String message = describe(e);
            log.warn("job {} ({}) failed: {}", job.id(), job.type(), message, e instanceof ApiException ? null : e);
            ctx.event("ERROR", message);
            try {
                handler.onFailure(ctx, e);
            } catch (Exception cleanup) {
                log.error("cleanup of job {} failed", job.id(), cleanup);
            }
            jobs.fail(job.id(), message);
            audit.failure("job.finish", "job", job.id(), Map.of("type", job.type(), "error", message));
        }
    }

    static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    @PreDestroy
    void shutdown() {
        ready = false;
        executor.shutdownNow();
    }

    private final class Ctx implements JobContext {
        private final UUID id;
        private final String params;

        Ctx(UUID id, String params) {
            this.id = id;
            this.params = params;
        }

        @Override
        public UUID jobId() {
            return id;
        }

        @Override
        public <T> T params(Class<T> type) {
            return json.read(params, type);
        }

        @Override
        public void info(String message) {
            event("INFO", message);
        }

        @Override
        public void warn(String message) {
            event("WARN", message);
        }

        void event(String level, String message) {
            jobs.event(id, level, message);
        }

        @Override
        public void progress(int percent, String message) {
            jobs.progress(id, Math.max(0, Math.min(99, percent)), message);
            event("INFO", message);
        }

        @Override
        public void result(Map<String, ?> result) {
            jobs.result(id, result);
        }
    }
}
