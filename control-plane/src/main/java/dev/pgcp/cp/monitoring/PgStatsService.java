package dev.pgcp.cp.monitoring;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.pg.InstanceConnector;

/** Read-only statistics, safe for MONITOR_ONLY instances. Requires pg_monitor for full query text. */
@Service
public class PgStatsService {

    public record Session(int pid, String database, String user, String application, String client, String state,
            String waitEventType, String waitEvent, Long querySeconds, Long transactionSeconds, String query) {
    }

    public record BlockedSession(int pid, String user, String database, Long seconds, List<Integer> blockedBy, String query) {
    }

    public record DatabaseStats(String database, int backends, long commits, long rollbacks, double cacheHitRatio,
            long tuplesInserted, long tuplesUpdated, long tuplesDeleted, long deadlocks, long tempBytes) {
    }

    public record Archiver(long archivedCount, Instant lastArchivedAt, long failedCount, Instant lastFailedAt,
            String archiveMode) {
    }

    public record Overview(String version, long uptimeSeconds, int maxConnections, int connections,
            Map<String, Integer> connectionsByState, int longRunningQueries, int blockedSessions, int replicas,
            Archiver archiver, List<DatabaseStats> databases) {
    }

    static final int LONG_QUERY_SECONDS = 60;

    private final InstanceRepository instances;
    private final InstanceConnector connector;

    public PgStatsService(InstanceRepository instances, InstanceConnector connector) {
        this.instances = instances;
        this.connector = connector;
    }

    public Overview overview(UUID instanceId) {
        Instance i = instances.require(instanceId);
        return connector.withMaintenance(i, c -> {
            String version;
            long uptime;
            int maxConn;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    select version(), extract(epoch from now() - pg_postmaster_start_time())::bigint,
                           current_setting('max_connections')::int
                    """)) {
                rs.next();
                version = rs.getString(1);
                uptime = rs.getLong(2);
                maxConn = rs.getInt(3);
            }
            Map<String, Integer> byState = new TreeMap<>();
            int total = 0;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    select coalesce(state, 'unknown'), count(*) from pg_stat_activity
                    where backend_type = 'client backend' group by 1
                    """)) {
                while (rs.next()) {
                    byState.put(rs.getString(1), rs.getInt(2));
                    total += rs.getInt(2);
                }
            }
            int longRunning = queryInt(c, "select count(*) from pg_stat_activity where state = 'active' "
                    + "and backend_type = 'client backend' and now() - query_start > interval '" + LONG_QUERY_SECONDS + " seconds'");
            int blocked = queryInt(c, "select count(*) from pg_stat_activity where cardinality(pg_blocking_pids(pid)) > 0");
            int replicas = queryInt(c, "select count(*) from pg_stat_replication");
            Archiver archiver;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    select archived_count, last_archived_time, failed_count, last_failed_time, current_setting('archive_mode')
                    from pg_stat_archiver
                    """)) {
                rs.next();
                archiver = new Archiver(rs.getLong(1), instant(rs, 2), rs.getLong(3), instant(rs, 4), rs.getString(5));
            }
            return new Overview(version, uptime, maxConn, total, byState, longRunning, blocked, replicas, archiver,
                    databaseStats(c));
        });
    }

    public List<Session> activity(UUID instanceId) {
        Instance i = instances.require(instanceId);
        return connector.withMaintenance(i, c -> {
            List<Session> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    select pid, datname, usename, application_name, client_addr::text, state, wait_event_type, wait_event,
                           extract(epoch from now() - query_start)::bigint, extract(epoch from now() - xact_start)::bigint,
                           left(query, 2000)
                    from pg_stat_activity
                    where pid <> pg_backend_pid() and backend_type = 'client backend'
                    order by query_start nulls last
                    """)) {
                while (rs.next()) {
                    out.add(new Session(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), (Long) rs.getObject(9), (Long) rs.getObject(10),
                            rs.getString(11)));
                }
            }
            return out;
        });
    }

    public List<BlockedSession> locks(UUID instanceId) {
        Instance i = instances.require(instanceId);
        return connector.withMaintenance(i, c -> {
            List<BlockedSession> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                    select pid, usename, datname, extract(epoch from now() - query_start)::bigint, pg_blocking_pids(pid),
                           left(query, 1000)
                    from pg_stat_activity where cardinality(pg_blocking_pids(pid)) > 0 order by query_start
                    """)) {
                while (rs.next()) {
                    Integer[] blockers = (Integer[]) rs.getArray(5).getArray();
                    out.add(new BlockedSession(rs.getInt(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4),
                            List.of(blockers), rs.getString(6)));
                }
            }
            return out;
        });
    }

    private static List<DatabaseStats> databaseStats(Connection c) throws SQLException {
        List<DatabaseStats> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                select datname, numbackends, xact_commit, xact_rollback,
                       case when blks_hit + blks_read = 0 then 1 else blks_hit::float / (blks_hit + blks_read) end,
                       tup_inserted, tup_updated, tup_deleted, deadlocks, temp_bytes
                from pg_stat_database where datname is not null and datname not like 'template%' order by datname
                """)) {
            while (rs.next()) {
                out.add(new DatabaseStats(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4),
                        Math.round(rs.getDouble(5) * 10000) / 10000.0, rs.getLong(6), rs.getLong(7), rs.getLong(8),
                        rs.getLong(9), rs.getLong(10)));
            }
        }
        return out;
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        var ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
