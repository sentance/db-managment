package dev.pgcp.cp.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;

@Service
public class JobService {

    public record Job(UUID id, String type, String status, String resourceKey, String title, int progress, String message,
            String error, int attempts, String createdBy, Instant createdAt, Instant startedAt, Instant finishedAt,
            Map<String, Object> result) {
    }

    public record Event(long id, Instant ts, String level, String message) {
    }

    public record Submitted(UUID jobId) {
    }

    private static final String COLUMNS = """
            id, type, status, resource_key, title, progress, message, error, attempts, created_by, created_at, started_at,
            finished_at, result::text as result
            """;

    private final JdbcClient jdbc;
    private final Json json;
    private final AuditService audit;

    public JobService(JdbcClient jdbc, Json json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    /**
     * Queues a job. Rejected with 409 if another job for the same resource is pending or running.
     * Params must not contain secrets (store them via SecretStore and pass the id).
     */
    public UUID submit(String type, String resourceKey, String title, Object params) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into job (id, type, status, resource_key, title, params, created_by)
                    values (?, ?, 'PENDING', ?, ?, ?::jsonb, ?)
                    """).params(id, type, resourceKey, title, json.write(params), actor()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("another operation is already in progress for this resource");
        }
        audit.success("job.submit", "job", id, Map.of("type", type, "resource", resourceKey, "title", title));
        return id;
    }

    public Optional<Job> get(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from job where id = ?").param(id).query(this::map).optional();
    }

    public List<Job> list(int limit, String status, String resourceKey) {
        return jdbc.sql("select " + COLUMNS + """
                 from job
                where (?::text is null or status = ?) and (?::text is null or resource_key = ?)
                order by created_at desc limit ?
                """).params(status, status, resourceKey, resourceKey, Math.min(Math.max(limit, 1), 500))
                .query(this::map).list();
    }

    public List<Event> events(UUID jobId) {
        return jdbc.sql("select id, ts, level, message from job_event where job_id = ? order by id").param(jobId)
                .query((rs, n) -> new Event(rs.getLong("id"), rs.getTimestamp("ts").toInstant(), rs.getString("level"),
                        rs.getString("message")))
                .list();
    }

    public boolean hasActive(String resourceKey) {
        return jdbc.sql("select count(*) from job where resource_key = ? and status in ('PENDING', 'RUNNING')")
                .param(resourceKey).query(Long.class).single() > 0;
    }

    // --- worker side ---------------------------------------------------------------------------------------------

    record Claimed(UUID id, String type, String params, int attempts) {
    }

    Optional<Claimed> claimNext() {
        return jdbc.sql("""
                update job set status = 'RUNNING', started_at = coalesce(started_at, now()), attempts = attempts + 1
                where id = (select id from job where status = 'PENDING' order by created_at for update skip locked limit 1)
                returning id, type, params::text as params, attempts
                """).query(Claimed.class).optional();
    }

    void event(UUID jobId, String level, String message) {
        jdbc.sql("insert into job_event (job_id, level, message) values (?, ?, ?)").params(jobId, level, truncate(message))
                .update();
    }

    void progress(UUID jobId, int percent, String message) {
        jdbc.sql("update job set progress = ?, message = ? where id = ?").params(percent, truncate(message), jobId).update();
    }

    void result(UUID jobId, Map<String, ?> result) {
        jdbc.sql("update job set result = ?::jsonb where id = ?").params(json.write(result), jobId).update();
    }

    void succeed(UUID jobId) {
        jdbc.sql("update job set status = 'SUCCEEDED', progress = 100, finished_at = now() where id = ?").param(jobId).update();
    }

    void fail(UUID jobId, String error) {
        jdbc.sql("update job set status = 'FAILED', error = ?, finished_at = now() where id = ?").params(truncate(error), jobId)
                .update();
    }

    void requeue(UUID jobId) {
        jdbc.sql("update job set status = 'PENDING' where id = ?").param(jobId).update();
    }

    List<Claimed> running() {
        return jdbc.sql("select id, type, params::text as params, attempts from job where status = 'RUNNING'")
                .query(Claimed.class).list();
    }

    private Job map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Job((UUID) rs.getObject("id"), rs.getString("type"), rs.getString("status"), rs.getString("resource_key"),
                rs.getString("title"), rs.getInt("progress"), rs.getString("message"), rs.getString("error"),
                rs.getInt("attempts"), rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("result") == null ? null : json.readMap(rs.getString("result")));
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        var ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 4000 ? s : s.substring(0, 4000) + "…";
    }

    private static String actor() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? "system" : auth.getName();
    }
}
