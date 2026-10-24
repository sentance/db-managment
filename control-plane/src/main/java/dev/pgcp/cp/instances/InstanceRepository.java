package dev.pgcp.cp.instances;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;

@Repository
public class InstanceRepository {

    private static final String COLUMNS = """
            id, server_id, name, kind, status, status_message, pg_major, pg_version, image, libc, container_name, host, port,
            ssl_mode, bind_address, host_port, extra_networks, external_mode, protection, memory_mb, cpus, settings::text as settings,
            maintenance_db, admin_role, admin_secret_id, superuser_secret_id, created_at
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public InstanceRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Instance> listActive() {
        return jdbc.sql("select " + COLUMNS + " from instance where status <> 'DELETED' order by name").query(this::map)
                .list();
    }

    public Optional<Instance> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from instance where id = ?").param(id).query(this::map).optional();
    }

    public Instance require(UUID id) {
        return find(id).filter(i -> !Instance.DELETED.equals(i.status())).orElseThrow(() -> ApiException.notFound("instance"));
    }

    public boolean nameTaken(String name) {
        return jdbc.sql("select count(*) from instance where name = ? and status <> 'DELETED'").param(name)
                .query(Long.class).single() > 0;
    }

    public boolean hostPortTaken(int port) {
        return jdbc.sql("select count(*) from instance where host_port = ? and status <> 'DELETED'").param(port)
                .query(Long.class).single() > 0;
    }

    public void insert(Instance i) {
        jdbc.sql("""
                insert into instance (id, server_id, name, kind, status, status_message, pg_major, pg_version, image, libc,
                    container_name, host, port, ssl_mode, bind_address, host_port, extra_networks, external_mode, protection,
                    memory_mb, cpus, settings, maintenance_db, admin_role, admin_secret_id, superuser_secret_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """)
                .params(i.id(), i.serverId(), i.name(), i.kind().name(), i.status(), i.statusMessage(), i.pgMajor(),
                        i.pgVersion(), i.image(), i.libc(), i.containerName(), i.host(), i.port(), i.sslMode(),
                        i.bindAddress(), i.hostPort(), i.extraNetworks().toArray(String[]::new),
                        i.externalMode() == null ? null : i.externalMode().name(), i.protection().name(), i.memoryMb(),
                        i.cpus(), json.write(i.settings()), i.maintenanceDb(), i.adminRole(), i.adminSecretId(),
                        i.superuserSecretId())
                .update();
    }

    public void updateStatus(UUID id, String status, String message) {
        jdbc.sql("update instance set status = ?, status_message = ?, updated_at = now() where id = ?")
                .params(status, message, id).update();
    }

    public void updateProvisioned(UUID id, String containerName, String image, String libc, String host, int port) {
        jdbc.sql("""
                update instance set container_name = ?, image = ?, libc = ?, host = ?, port = ?, updated_at = now()
                where id = ?
                """).params(containerName, image, libc, host, port, id).update();
    }

    public void updateVersion(UUID id, String version) {
        jdbc.sql("update instance set pg_version = ?, updated_at = now() where id = ?").params(version, id).update();
    }

    public void updateProtection(UUID id, Instance.Protection protection) {
        jdbc.sql("update instance set protection = ?, updated_at = now() where id = ?").params(protection.name(), id).update();
    }

    public void updateExternalMode(UUID id, Instance.ExternalMode mode) {
        jdbc.sql("update instance set external_mode = ?, updated_at = now() where id = ? and kind = 'EXTERNAL'")
                .params(mode.name(), id).update();
    }

    private Instance map(ResultSet rs, int n) throws SQLException {
        Map<String, String> settings = new TreeMap<>();
        json.readMap(rs.getString("settings")).forEach((k, v) -> settings.put(k, String.valueOf(v)));
        Array nets = rs.getArray("extra_networks");
        String ext = rs.getString("external_mode");
        return new Instance((UUID) rs.getObject("id"), (UUID) rs.getObject("server_id"), rs.getString("name"),
                Instance.Kind.valueOf(rs.getString("kind")), rs.getString("status"), rs.getString("status_message"),
                (Integer) rs.getObject("pg_major"), rs.getString("pg_version"), rs.getString("image"), rs.getString("libc"),
                rs.getString("container_name"), rs.getString("host"), rs.getInt("port"), rs.getString("ssl_mode"),
                rs.getString("bind_address"), (Integer) rs.getObject("host_port"),
                nets == null ? List.of() : List.of((String[]) nets.getArray()),
                ext == null ? null : Instance.ExternalMode.valueOf(ext), Instance.Protection.valueOf(rs.getString("protection")),
                (Integer) rs.getObject("memory_mb"), (BigDecimal) rs.getObject("cpus"), settings, rs.getString("maintenance_db"),
                rs.getString("admin_role"), (Long) rs.getObject("admin_secret_id"), (Long) rs.getObject("superuser_secret_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
