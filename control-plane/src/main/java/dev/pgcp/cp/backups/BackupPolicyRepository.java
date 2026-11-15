package dev.pgcp.cp.backups;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import dev.pgcp.agent.api.*;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;

@Repository
public class BackupPolicyRepository {
    public record Row(UUID id,String name,long revision,long templateSecretId,Long appliedRevision,
            BackupPolicyStatus agentStatus,Instant checkedAt,String syncError,Instant createdAt) { }
    private final JdbcClient jdbc;
    private final Json json;
    public BackupPolicyRepository(JdbcClient jdbc,Json json) { this.jdbc=jdbc;this.json=json; }
    public List<Row> list() {
        return jdbc.sql("select * from backup_policy order by created_at,id").query((r,n)->new Row(
                (UUID)r.getObject("id"),r.getString("name"),r.getLong("revision"),r.getLong("template_secret_id"),
                (Long)r.getObject("applied_revision"),r.getString("agent_status")==null?null:json.read(r.getString("agent_status"),BackupPolicyStatus.class),
                r.getTimestamp("checked_at")==null?null:r.getTimestamp("checked_at").toInstant(),r.getString("sync_error"),r.getTimestamp("created_at").toInstant())).list();
    }
    public Row require(UUID id) { return list().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->ApiException.notFound("backup policy")); }
    public Row lock(UUID id) {
        jdbc.sql("select id from backup_policy where id=? for update").param(id).query(UUID.class).optional()
                .orElseThrow(()->ApiException.notFound("backup policy"));
        return require(id);
    }
    public void insert(UUID id,String name,long secret) {
        jdbc.sql("insert into backup_policy(id,name,revision,template_secret_id) values(?,?,1,?)").params(id,name,secret).update();
    }
    public void revision(UUID id,String name,long revision) {
        jdbc.sql("update backup_policy set name=?,revision=?,sync_error=null where id=?").params(name,revision,id).update();
    }
    public void observed(BackupPolicyStatus status) {
        jdbc.sql("update backup_policy set applied_revision=?,agent_status=?::jsonb,checked_at=now(),sync_error=null where id=?")
                .params(status.revision(),json.write(status),status.id()).update();
    }
    public void unavailable(UUID id) {
        jdbc.sql("update backup_policy set sync_error='Agent unavailable or policy rejected; execution state is unconfirmed' where id=?").param(id).update();
    }

    /** The caller acknowledges the agent only after this proxied transaction returns (after commit). */
    @Transactional
    public void ingest(BackupPolicyEvent event) {
        if (!List.of("SUCCEEDED","FAILED","EXPIRING","EXPIRED").contains(event.status())) throw ApiException.badRequest("invalid policy result");
        require(event.policyId()); // Missing references remain on the agent for recovery/reconciliation.
        int inserted=jdbc.sql("insert into backup_policy_event(id,policy_id,backup_id) values(?,?,?) on conflict do nothing")
                .params(event.id(),event.policyId(),event.backupId()).update();
        if(inserted==0) return;
        jdbc.sql("""
                insert into logical_backup(id,instance_id,database_name,destination_id,container_id,pg_major,status,result,created_at,policy_id)
                values(?,?,?,?,?,?,?,?::jsonb,?,?)
                on conflict(id) do update set status=case
                  when logical_backup.status='EXPIRED' then 'EXPIRED'
                  when excluded.status='EXPIRED' then 'EXPIRED'
                  when logical_backup.status='EXPIRING' or excluded.status='EXPIRING' then 'EXPIRING'
                  else excluded.status end
                """).params(event.backupId(),event.instanceId(),event.database(),event.destinationId(),event.containerId(),event.pgMajor(),
                event.status(),json.write(event.result()),OffsetDateTime.ofInstant(event.startedAt(),ZoneOffset.UTC),event.policyId()).update();
    }
}
