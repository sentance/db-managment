package dev.pgcp.cp.databases;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Databases and roles that pgcp created or adopted, with references to their encrypted passwords. */
@Repository
public class ManagedObjects {

    public enum RoleKind {
        OWNER,
        READ_WRITE,
        READ_ONLY,
        CUSTOM
    }

    public record ManagedRole(UUID id, UUID instanceId, String name, RoleKind kind, Long secretId, boolean locked,
            Instant createdAt, Instant rotatedAt) {
    }

    public record ManagedDatabase(UUID id, UUID instanceId, String name, String ownerRole, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public ManagedObjects(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDatabase(UUID instanceId, String name, String owner) {
        jdbc.sql("""
                insert into managed_database (id, instance_id, name, owner_role) values (?, ?, ?, ?)
                on conflict (instance_id, name) do nothing
                """).params(UUID.randomUUID(), instanceId, name, owner).update();
    }

    public Optional<ManagedDatabase> findDatabase(UUID instanceId, String name) {
        return jdbc.sql("select id, instance_id, name, owner_role, created_at from managed_database where instance_id = ? and name = ?")
                .params(instanceId, name).query(ManagedDatabase.class).optional();
    }

    public List<ManagedDatabase> databases(UUID instanceId) {
        return jdbc.sql("select id, instance_id, name, owner_role, created_at from managed_database where instance_id = ? order by name")
                .param(instanceId).query(ManagedDatabase.class).list();
    }

    public long countDatabases() {
        return jdbc.sql("""
                select count(*) from managed_database d join instance i on i.id = d.instance_id where i.status <> 'DELETED'
                """).query(Long.class).single();
    }

    public void deleteDatabase(UUID instanceId, String name) {
        jdbc.sql("delete from managed_database where instance_id = ? and name = ?").params(instanceId, name).update();
    }

    public void renameDatabase(UUID instanceId, String from, String to) {
        jdbc.sql("update managed_database set name = ? where instance_id = ? and name = ?").params(to, instanceId, from).update();
    }

    public UUID insertRole(UUID instanceId, String name, RoleKind kind, long secretId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into managed_role (id, instance_id, name, kind, secret_id) values (?, ?, ?, ?, ?)")
                .params(id, instanceId, name, kind.name(), secretId).update();
        return id;
    }

    public Optional<ManagedRole> findRole(UUID instanceId, String name) {
        return jdbc.sql("""
                select id, instance_id, name, kind, secret_id, locked, created_at, rotated_at
                from managed_role where instance_id = ? and name = ?
                """).params(instanceId, name).query(ManagedRole.class).optional();
    }

    public List<ManagedRole> roles(UUID instanceId) {
        return jdbc.sql("""
                select id, instance_id, name, kind, secret_id, locked, created_at, rotated_at
                from managed_role where instance_id = ? order by name
                """).param(instanceId).query(ManagedRole.class).list();
    }

    public void markRotated(UUID roleId) {
        jdbc.sql("update managed_role set rotated_at = now() where id = ?").param(roleId).update();
    }

    public void setLocked(UUID roleId, boolean locked) {
        jdbc.sql("update managed_role set locked = ? where id = ?").params(locked, roleId).update();
    }

    public void deleteRole(UUID roleId) {
        jdbc.sql("delete from managed_role where id = ?").param(roleId).update();
    }
}
