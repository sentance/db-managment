package dev.pgcp.cp.instances;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import dev.pgcp.cp.common.ApiException;

/** Allowlist of PostgreSQL parameters that can be set from the UI, plus memory-based defaults. */
public final class PgSettings {

    private enum Type {
        MEMORY("^\\d{1,7}(kB|MB|GB)$"),
        DURATION("^\\d{1,9}(us|ms|s|min|h|d)?$"),
        INT("^\\d{1,9}$"),
        REAL("^\\d{1,6}(\\.\\d{1,4})?$"),
        BOOL("^(on|off)$"),
        WORD("^[A-Za-z0-9_./+-]{1,64}$");

        final Pattern pattern;

        Type(String regex) {
            this.pattern = Pattern.compile(regex);
        }
    }

    private static final Map<String, Type> ALLOWED = new TreeMap<>();

    static {
        for (String k : new String[] { "shared_buffers", "effective_cache_size", "work_mem", "maintenance_work_mem",
                "max_wal_size", "min_wal_size", "wal_buffers", "temp_buffers", "autovacuum_work_mem" }) {
            ALLOWED.put(k, Type.MEMORY);
        }
        for (String k : new String[] { "statement_timeout", "idle_in_transaction_session_timeout", "lock_timeout",
                "log_min_duration_statement", "checkpoint_timeout", "autovacuum_naptime", "idle_session_timeout" }) {
            ALLOWED.put(k, Type.DURATION);
        }
        for (String k : new String[] { "max_connections", "default_statistics_target", "max_worker_processes",
                "max_parallel_workers", "max_parallel_workers_per_gather", "max_parallel_maintenance_workers",
                "effective_io_concurrency", "autovacuum_max_workers", "max_locks_per_transaction" }) {
            ALLOWED.put(k, Type.INT);
        }
        for (String k : new String[] { "random_page_cost", "seq_page_cost", "checkpoint_completion_target",
                "autovacuum_vacuum_scale_factor", "autovacuum_analyze_scale_factor" }) {
            ALLOWED.put(k, Type.REAL);
        }
        for (String k : new String[] { "track_io_timing", "log_lock_waits", "log_checkpoints", "jit",
                "log_connections", "log_disconnections" }) {
            ALLOWED.put(k, Type.BOOL);
        }
        for (String k : new String[] { "timezone", "log_timezone", "wal_compression" }) {
            ALLOWED.put(k, Type.WORD);
        }
    }

    private PgSettings() {
    }

    public static Map<String, String> allowedTypes() {
        Map<String, String> out = new TreeMap<>();
        ALLOWED.forEach((k, t) -> out.put(k, t.name()));
        return out;
    }

    /** Conservative defaults for a container memory limit, overridden by explicit user settings. */
    public static Map<String, String> withDefaults(int memoryMb, Map<String, String> overrides) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("shared_buffers", Math.max(32, memoryMb / 4) + "MB");
        s.put("effective_cache_size", Math.max(64, memoryMb * 3 / 5) + "MB");
        s.put("maintenance_work_mem", Math.max(16, Math.min(1024, memoryMb / 16)) + "MB");
        s.put("work_mem", Math.max(1, Math.min(64, memoryMb / 256)) + "MB");
        s.put("max_connections", memoryMb < 1024 ? "50" : "100");
        s.put("wal_compression", "on");
        s.put("random_page_cost", "1.1");
        s.put("effective_io_concurrency", "200");
        s.put("log_min_duration_statement", "1s");
        s.put("log_lock_waits", "on");
        s.put("track_io_timing", "on");
        s.put("idle_in_transaction_session_timeout", "10min");
        validate(overrides).forEach(s::put);
        return s;
    }

    public static Map<String, String> validate(Map<String, String> settings) {
        if (settings == null) {
            return Map.of();
        }
        settings.forEach((k, v) -> {
            Type t = ALLOWED.get(k);
            if (t == null) {
                throw ApiException.badRequest("PostgreSQL parameter not allowed: " + k);
            }
            if (v == null || !t.pattern.matcher(v).matches()) {
                throw ApiException.badRequest("invalid value for " + k + " (expected " + t.name().toLowerCase() + ")");
            }
        });
        return settings;
    }
}
