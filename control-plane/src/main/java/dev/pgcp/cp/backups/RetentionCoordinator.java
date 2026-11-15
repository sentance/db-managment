package dev.pgcp.cp.backups;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import dev.pgcp.cp.agentclient.AgentClient;

@Service
public class RetentionCoordinator {
    record Completed(UUID id,UUID backupId,String status) { }
    private final JdbcClient jdbc;private final AgentClient agent;
    public RetentionCoordinator(JdbcClient jdbc,AgentClient agent) { this.jdbc=jdbc;this.agent=agent; }
    @Scheduled(fixedDelay=10000,initialDelayString="${pgcp.protection.initial-delay:5s}")
    public void reconcile() {
        for(var r:jdbc.sql("select id,backup_id,status from logical_restore where status in ('SUCCEEDED','FAILED') and not retention_released order by created_at limit 100").query(Completed.class).list()) {
            try {
                agent.releaseBackup(r.backupId(),r.id(),r.status().equals("SUCCEEDED"));
                jdbc.sql("update logical_restore set retention_released=true where id=?").param(r.id()).update();
            } catch(RuntimeException ignored) { /* Keep the durable pin until the agent confirms release, including across CP restart. */ }
        }
    }
}
