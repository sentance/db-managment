package dev.pgcp.cp.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import dev.pgcp.cp.common.Json;

/**
 * Append-only audit trail. Each entry's hash covers the previous entry's hash, so deleting or editing rows
 * (which the database trigger also forbids) is detectable by {@link #verify()}.
 * Callers must never put secrets into details.
 */
@Service
public class AuditService {

    public record Entry(long id, Instant ts, String actor, String sourceIp, String action, String targetType,
            String targetId, String outcome, Map<String, Object> details) {
    }

    public record Verification(boolean intact, long checked, Long firstBrokenId) {
    }

    private static final long CHAIN_LOCK = 0x70676370_61756469L;

    private final JdbcClient jdbc;
    private final Json json;

    public AuditService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void success(String action, String targetType, Object targetId, Map<String, ?> details) {
        record(action, targetType, targetId, "SUCCESS", details);
    }

    public void failure(String action, String targetType, Object targetId, Map<String, ?> details) {
        record(action, targetType, targetId, "FAILURE", details);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Object targetId, String outcome, Map<String, ?> details) {
        jdbc.sql("select pg_advisory_xact_lock(?)").param(CHAIN_LOCK).query().singleRow();
        byte[] prev = jdbc.sql("select hash from audit_log order by id desc limit 1").query(byte[].class).optional().orElse(null);
        // Truncate to microseconds: that is what timestamptz stores, and verify() must recompute the same bytes.
        Instant ts = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String actor = currentActor();
        String ip = currentIp();
        String detailsJson = json.write(new TreeMap<>(details == null ? Map.of() : details));
        String target = targetId == null ? null : targetId.toString();
        byte[] hash = hash(prev, ts, actor, ip, action, targetType, target, outcome, detailsJson);
        jdbc.sql("""
                insert into audit_log (ts, actor, source_ip, action, target_type, target_id, outcome, details, prev_hash, hash)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """)
                .params(OffsetDateTime.ofInstant(ts, ZoneOffset.UTC), actor, ip, action, targetType, target, outcome,
                        detailsJson, prev, hash)
                .update();
    }

    public List<Entry> list(int limit, Long beforeId) {
        return jdbc.sql("""
                select id, ts, actor, source_ip, action, target_type, target_id, outcome, details::text as details
                from audit_log where (?::bigint is null or id < ?) order by id desc limit ?
                """)
                .params(beforeId, beforeId, Math.min(Math.max(limit, 1), 500))
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getTimestamp("ts").toInstant(), rs.getString("actor"),
                        rs.getString("source_ip"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("outcome"), json.readMap(rs.getString("details"))))
                .list();
    }

    public Verification verify() {
        record Row(long id, OffsetDateTime ts, String actor, String sourceIp, String action, String targetType,
                String targetId, String outcome, String details, byte[] prevHash, byte[] hash) {
        }
        List<Row> rows = jdbc.sql("""
                select id, ts, actor, source_ip, action, target_type, target_id, outcome, details::text as details,
                       prev_hash, hash
                from audit_log order by id
                """).query(Row.class).list();
        byte[] prev = null;
        for (Row r : rows) {
            // jsonb normalizes key order and spacing; re-serialize the same way the writer did.
            String detailsJson = json.write(new TreeMap<>(json.readMap(r.details())));
            byte[] expected = hash(prev, r.ts().toInstant(), r.actor(), r.sourceIp(), r.action(), r.targetType(),
                    r.targetId(), r.outcome(), detailsJson);
            if (!MessageDigest.isEqual(expected, r.hash()) || !MessageDigest.isEqual(prev == null ? new byte[0] : prev,
                    r.prevHash() == null ? new byte[0] : r.prevHash())) {
                return new Verification(false, rows.size(), r.id());
            }
            prev = r.hash();
        }
        return new Verification(true, rows.size(), null);
    }

    static byte[] hash(byte[] prev, Instant ts, String... fields) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            if (prev != null) {
                md.update(prev);
            }
            md.update(ts.toString().getBytes(StandardCharsets.UTF_8));
            for (String f : fields) {
                md.update((byte) 0x1f);
                if (f != null) {
                    md.update(f.getBytes(StandardCharsets.UTF_8));
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return "system";
        }
        return auth.getName();
    }

    static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
