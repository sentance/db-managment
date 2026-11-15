package dev.pgcp.cp.backups;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;

@Repository
public class LogicalRepository {
    public record Destination(UUID id, String name, String endpoint, String region, String bucket, String prefix,
            String recipient, long credentialsSecretId, long identitySecretId, String status, Instant testedAt) { }
    public record Backup(UUID id, UUID instanceId, String databaseName, UUID destinationId, String containerId,
            int pgMajor, String status, UUID jobId, Map<String,Object> result, Instant verifiedAt, Instant createdAt) { }
    public record Restore(UUID id, UUID backupId, String targetName, String databaseName, String ownerRole,
            UUID targetInstanceId, UUID jobId, String status, Map<String,Object> result, Instant createdAt) { }
    private final JdbcClient jdbc;
    private final Json json;
    public LogicalRepository(JdbcClient jdbc, Json json) { this.jdbc=jdbc; this.json=json; }

    public List<Destination> destinations() {
        return jdbc.sql("select id,name,endpoint,region,bucket,prefix,recipient,credentials_secret_id,identity_secret_id,status,tested_at from backup_destination order by name")
                .query(Destination.class).list();
    }
    public Destination destination(UUID id) { return destinations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("backup destination")); }
    public void destination(Destination d) {
        jdbc.sql("insert into backup_destination(id,name,endpoint,region,bucket,prefix,recipient,credentials_secret_id,identity_secret_id) values (?,?,?,?,?,?,?,?,?)")
                .params(d.id(),d.name(),d.endpoint(),d.region(),d.bucket(),d.prefix(),d.recipient(),d.credentialsSecretId(),d.identitySecretId()).update();
    }
    public void destinationStatus(UUID id, boolean ok) {
        jdbc.sql("update backup_destination set status=?,tested_at=now() where id=?").params(ok?"VERIFIED":"FAILED",id).update();
    }
    public List<Backup> backups() {
        return backups(null);
    }
    private List<Backup> backups(UUID id) {
        return jdbc.sql("select * from logical_backup where (?::uuid is null or id=?) order by created_at desc limit ?")
                .params(id,id,id==null?500:1).query((r,n)->new Backup(
                (UUID)r.getObject("id"),(UUID)r.getObject("instance_id"),r.getString("database_name"),(UUID)r.getObject("destination_id"),
                r.getString("container_id"),r.getInt("pg_major"),r.getString("status"),(UUID)r.getObject("job_id"),
                json.readMap(r.getString("result")),r.getTimestamp("verified_at")==null?null:r.getTimestamp("verified_at").toInstant(),r.getTimestamp("created_at").toInstant())).list();
    }
    public Backup backup(UUID id) { return backups(id).stream().findFirst().orElseThrow(()->ApiException.notFound("logical backup")); }
    public void insertBackup(UUID id, UUID instance, String database, UUID destination, String container, int major) {
        jdbc.sql("insert into logical_backup(id,instance_id,database_name,destination_id,container_id,pg_major) values(?,?,?,?,?,?)")
                .params(id,instance,database,destination,container,major).update();
    }
    public void backupJob(UUID id, UUID job) { jdbc.sql("update logical_backup set job_id=? where id=?").params(job,id).update(); }
    public void backupStatus(UUID id, String status, Map<String,?> result) {
        jdbc.sql("update logical_backup set status=?,result=coalesce(?::jsonb,result) where id=?").params(status,result==null?null:json.write(result),id).update();
    }
    public List<Restore> restores() {
        return restores(null);
    }
    private List<Restore> restores(UUID id) {
        return jdbc.sql("select * from logical_restore where (?::uuid is null or id=?) order by created_at desc limit ?")
                .params(id,id,id==null?500:1).query((r,n)->new Restore(
                (UUID)r.getObject("id"),(UUID)r.getObject("backup_id"),r.getString("target_name"),r.getString("database_name"),r.getString("owner_role"),
                (UUID)r.getObject("target_instance_id"),(UUID)r.getObject("job_id"),r.getString("status"),json.readMap(r.getString("result")),r.getTimestamp("created_at").toInstant())).list();
    }
    public Restore restore(UUID id) { return restores(id).stream().findFirst().orElseThrow(()->ApiException.notFound("restore")); }
    public void insertRestore(UUID id, UUID backup, String name, String database, String owner) {
        jdbc.sql("insert into logical_restore(id,backup_id,target_name,database_name,owner_role) values(?,?,?,?,?)").params(id,backup,name,database,owner).update();
    }
    public void restoreJob(UUID id, UUID job) { jdbc.sql("update logical_restore set job_id=? where id=?").params(job,id).update(); }
    public void restoreTarget(UUID id, UUID target) { jdbc.sql("update logical_restore set target_instance_id=? where id=?").params(target,id).update(); }
    public void restoreStatus(UUID id, String status, Map<String,?> result) {
        jdbc.sql("update logical_restore set status=?,result=coalesce(?::jsonb,result) where id=?").params(status,result==null?null:json.write(result),id).update();
    }
    public void verified(UUID id) { jdbc.sql("update logical_backup set verified_at=now() where id=?").param(id).update(); }
}
