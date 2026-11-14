package dev.pgcp.cp.backups;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.pgcp.agent.api.BackupCatalogue;
import dev.pgcp.cp.common.Json;
import dev.pgcp.cp.monitoring.PgStatsService;

@Repository
public class BackupSourceRepository {
    public record Snapshot(BackupCatalogue catalogue, PgStatsService.Archiver archiver) { }
    public record Source(UUID instanceId, UUID bindingId, String containerName, String containerId, String stanza,
            int staleAfterHours, String inspectionState, Instant checkedAt, Instant lastSuccessAt,
            String error, Snapshot snapshot) { }
    private final JdbcClient jdbc;
    private final Json json;

    public BackupSourceRepository(JdbcClient jdbc, Json json) { this.jdbc = jdbc; this.json = json; }

    public List<Source> list() {
        return jdbc.sql("select * from backup_source").query(this::map).list();
    }

    private Source map(ResultSet rs, int row) throws SQLException {
        return new Source(
                (UUID) rs.getObject("instance_id"), (UUID) rs.getObject("binding_id"), rs.getString("container_name"),
                rs.getString("container_id"), rs.getString("stanza"), rs.getInt("stale_after_hours"),
                rs.getString("inspection_state"), rs.getTimestamp("checked_at") == null ? null : rs.getTimestamp("checked_at").toInstant(),
                rs.getTimestamp("last_success_at") == null ? null : rs.getTimestamp("last_success_at").toInstant(),
                rs.getString("error"), rs.getString("snapshot") == null ? null : json.read(rs.getString("snapshot"), Snapshot.class));
    }

    public Optional<Source> find(UUID instanceId) {
        return jdbc.sql("select * from backup_source where instance_id = ?").param(instanceId).query(this::map).optional();
    }

    public void bind(UUID instanceId, UUID bindingId, String containerName, String containerId, String stanza, int hours) {
        jdbc.sql("""
                insert into backup_source(instance_id, binding_id, container_name, container_id, stanza, stale_after_hours)
                values (?, ?, ?, ?, ?, ?)
                on conflict (instance_id) do update set binding_id = excluded.binding_id,
                    container_name = excluded.container_name, container_id = excluded.container_id,
                    stanza = excluded.stanza, stale_after_hours = excluded.stale_after_hours,
                    inspection_state = 'NEVER', checked_at = null, last_success_at = null,
                    error = null, snapshot = null, updated_at = now()
                """).params(instanceId, bindingId, containerName, containerId, stanza, hours).update();
    }

    public void success(Source source, Snapshot snapshot) {
        jdbc.sql("""
                update backup_source set inspection_state = 'OK', checked_at = now(), last_success_at = now(),
                    error = null, snapshot = ?::jsonb, updated_at = now() where instance_id = ? and binding_id = ?
                """).params(json.write(snapshot), source.instanceId(), source.bindingId()).update();
    }

    public void failure(Source source, String state, String message) {
        jdbc.sql("""
                update backup_source set inspection_state = ?, checked_at = now(), error = ?, updated_at = now()
                where instance_id = ? and binding_id = ?
                """).params(state, message, source.instanceId(), source.bindingId()).update();
    }

    public void remove(UUID instanceId) {
        jdbc.sql("delete from backup_source where instance_id = ?").param(instanceId).update();
    }
}
