package dev.pgcp.cp.notifications;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import dev.pgcp.cp.backups.BackupPolicyRepository;

@Service
public class BackupAlertCoordinator {
    private final BackupPolicyRepository policies;private final NotificationRepository alerts;private final JdbcClient jdbc;
    public BackupAlertCoordinator(BackupPolicyRepository policies,NotificationRepository alerts,JdbcClient jdbc) { this.policies=policies;this.alerts=alerts;this.jdbc=jdbc; }
    @Scheduled(fixedDelay=10000,initialDelayString="${pgcp.notifications.initial-delay:10s}")
    public void reconcile() {
        for(var p:policies.list()) {
            boolean fresh=p.checkedAt()!=null && p.checkedAt().plusSeconds(120).isAfter(Instant.now()) && p.syncError()==null;
            boolean unavailable=!fresh && (p.syncError()!=null || p.createdAt().plusSeconds(120).isBefore(Instant.now()));
            condition(p.id(),"AGENT_UNREACHABLE",unavailable,"Backup agent status is unavailable for policy "+p.name(),"Backup agent communication resumed for policy "+p.name());
            if(!fresh || p.agentStatus()==null || !Objects.equals(p.appliedRevision(),p.revision())) continue;
            var status=p.agentStatus();boolean paused=!status.enabled();
            for(String kind:List.of("BACKUP_FAILED","STALE","OVERDUE","RETENTION_FAILED")) {
                boolean active=kind.equals("RETENTION_FAILED")?"ERROR".equals(status.retentionHealth()):!paused && kind.equals(status.health());
                condition(p.id(),kind,active,description(kind)+" for policy "+p.name(),paused && !kind.equals("RETENTION_FAILED")?"Policy "+p.name()+" was paused; protection is disabled":description(kind)+" cleared for policy "+p.name());
            }
        }
        record Export(UUID instanceId,String instanceName,String databaseName,UUID destinationId,String status) { }
        for(var b:jdbc.sql("""
                select distinct on(b.instance_id,b.database_name,b.destination_id) b.instance_id,i.name as instance_name,b.database_name,b.destination_id,b.status
                from logical_backup b join instance i on i.id=b.instance_id where b.policy_id is null and b.status in ('SUCCEEDED','FAILED')
                order by b.instance_id,b.database_name,b.destination_id,b.created_at desc,b.id desc
                """).query(Export.class).list()) {
            alerts.condition("manual:"+b.instanceId()+":"+b.databaseName()+":"+b.destinationId(),"MANUAL_BACKUP_FAILED",b.instanceId(),b.status().equals("FAILED"),
                    "On-demand backup failed for "+b.instanceName()+" / "+b.databaseName(),"A later on-demand backup succeeded for "+b.instanceName()+" / "+b.databaseName());
        }
        record Restore(UUID backupId,String status,String targetName,String instanceName,String databaseName) { }
        for(var r:jdbc.sql("""
                select distinct on(r.backup_id) r.backup_id,r.status,r.target_name,i.name as instance_name,b.database_name
                from logical_restore r join logical_backup b on b.id=r.backup_id join instance i on i.id=b.instance_id
                where r.status in ('SUCCEEDED','FAILED') order by r.backup_id,r.created_at desc,r.id desc
                """).query(Restore.class).list()) {
            alerts.condition("restore:"+r.backupId(),"RESTORE_FAILED",r.backupId(),r.status().equals("FAILED"),
                    "Restore into "+r.targetName()+" failed for "+r.instanceName()+" / "+r.databaseName()+"; isolated target retained",
                    "Verified restore into "+r.targetName()+" succeeded for "+r.instanceName()+" / "+r.databaseName());
        }
    }
    private void condition(UUID policy,String kind,boolean active,String message,String recovery) { alerts.condition("policy:"+policy+":"+kind,kind,policy,active,message,recovery); }
    private static String description(String kind) { return switch(kind) {
        case "BACKUP_FAILED"->"Scheduled backup failure";case "STALE"->"Backup freshness threshold exceeded";
        case "OVERDUE"->"First backup overdue";default->"Backup retention failure";
    }; }
}
