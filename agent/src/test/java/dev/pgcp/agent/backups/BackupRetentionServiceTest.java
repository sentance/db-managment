package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.AgentException;
import tools.jackson.databind.json.JsonMapper;

class BackupRetentionServiceTest {
    @TempDir Path data;
    final JsonMapper json=JsonMapper.builder().build();
    final LogicalWorkerService workers=mock(LogicalWorkerService.class);
    final BackupPolicyServiceTest.MutableClock clock=new BackupPolicyServiceTest.MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
    final UUID policyId=UUID.randomUUID(),instance=UUID.randomUUID(),destination=UUID.randomUUID();
    final List<LogicalBackupRequest> deletions=new ArrayList<>();
    final Map<UUID,Operation> operations=new HashMap<>();
    BackupRetentionService service() throws Exception { return new BackupRetentionService(data,workers,json,clock); }
    BackupPolicy policy(int last,int days) {
        var storage=new LogicalBackupRequest.Storage("https://s3.example","auto","backups","pgcp/test","access","secret","age1"+"q".repeat(58),null);
        var template=new LogicalBackupRequest(policyId,LogicalBackupRequest.Action.BACKUP,instance,"source","a".repeat(64),18,
                new LogicalBackupRequest.Connection("source",5432,"app","backup","password","require",null),storage,
                "pgcp/test/"+policyId+".dump.age","pgcp/test/"+policyId+".manifest.age",null,null);
        return new BackupPolicy(policyId,1,destination,"02:00","UTC",true,false,26,template,last,days);
    }
    BackupPolicyEvent backup(int daysAgo) {
        UUID id=UUID.randomUUID();Instant time=clock.instant().minus(Duration.ofDays(daysAgo));
        return new BackupPolicyEvent(UUID.randomUUID(),policyId,1,id,instance,destination,"app","a".repeat(64),18,"SUCCEEDED",
                Map.of("artifactKey","pgcp/test/"+id+".dump.age","manifestKey","pgcp/test/"+id+".manifest.age","sha256","a".repeat(64)),time,time);
    }
    void workers(OperationStatus initial) throws Exception {
        when(workers.get(any())).thenAnswer(i->{var op=operations.get(i.getArgument(0));if(op==null) throw AgentException.notFound("missing");return op;});
        when(workers.start(any())).thenAnswer(i->{LogicalBackupRequest r=i.getArgument(0);r.validate(false);deletions.add(r);
            var op=operation(r.operationId(),initial);operations.put(op.id(),op);return op;});
    }
    Operation operation(UUID id,OperationStatus status) { return new Operation(id,OperationType.EXPIRE_BACKUP,"artifact",status,100,"done",null,clock.instant(),clock.instant(),Map.of("expired","true")); }
    @Test void protectsNewestBackupsVerifiedRecoveryPointPinsAndManualArtifactsAcrossRestart() throws Exception {
        workers(OperationStatus.SUCCEEDED);var retention=service();List<BackupPolicyEvent> saved=new ArrayList<>();
        for(int days=10;days>=6;days--) { var b=backup(days);saved.add(b);retention.record(b); }
        UUID pin=UUID.randomUUID();retention.pin(saved.get(0).backupId(),pin);
        UUID verify=UUID.randomUUID();retention.pin(saved.get(1).backupId(),verify);retention.release(saved.get(1).backupId(),verify,true);
        retention.reconcile(policy(0,0));assertThat(deletions).isEmpty();
        retention=service();retention.reconcile(policy(2,1));
        assertThat(deletions).hasSize(1);assertThat(deletions.getFirst().artifactKey()).isEqualTo(saved.get(2).result().get("artifactKey"));
        assertThat(deletions.getFirst().connection()).isNull();assertThat(deletions.getFirst().storage().identity()).isNull();
        retention.reconcile(policy(2,1));assertThat(deletions).hasSize(1);
        assertThat(retention.pin(UUID.randomUUID(),UUID.randomUUID())).isFalse();
        var restarted=service();assertThatThrownBy(()->restarted.pin(saved.get(2).backupId(),UUID.randomUUID())).hasMessageContaining("expiration");
        restarted.release(saved.get(0).backupId(),pin,false);restarted.reconcile(policy(2,1));assertThat(deletions).hasSize(2);
        assertThat(Files.readString(data.resolve("protection/retention/"+saved.get(2).backupId()+".json"))).doesNotContain("password","secret","access");
    }
    @Test void committedExpirationRejectsNewPinsAndReattachesAfterRestart() throws Exception {
        workers(OperationStatus.RUNNING);var retention=service();var oldest=backup(10);retention.record(oldest);retention.record(backup(8));retention.record(backup(6));
        retention.reconcile(policy(2,1));
        assertThatThrownBy(()->retention.pin(oldest.backupId(),UUID.randomUUID())).hasMessageContaining("expiration");
        var restarted=service();restarted.reconcile(policy(2,1));assertThat(deletions).hasSize(1);
        UUID op=deletions.getFirst().operationId();operations.put(op,operation(op,OperationStatus.SUCCEEDED));restarted.reconcile(policy(2,1));
        assertThat(Files.readString(data.resolve("protection/retention/"+oldest.backupId()+".json"))).contains("EXPIRED");
        assertThat(deletions).hasSize(1);
    }
    @Test void retriesFailedDeletionWithBackoffAndNeverTreatsAnExpiringBackupAsRestorable() throws Exception {
        workers(OperationStatus.FAILED);var retention=service();var oldest=backup(10);retention.record(oldest);retention.record(backup(8));retention.record(backup(6));
        retention.reconcile(policy(2,1));assertThat(retention.health(policyId)).isEqualTo("ERROR");
        retention.reconcile(policy(2,1));assertThat(deletions).hasSize(1);
        clock.now=clock.now.plusSeconds(31);retention=service();retention.reconcile(policy(2,1));
        assertThat(deletions).hasSize(2);assertThat(deletions.get(0).operationId()).isNotEqualTo(deletions.get(1).operationId());
    }
    @Test void ageWindowKeepsRecentBackupsEvenOutsideTheCount() throws Exception {
        workers(OperationStatus.SUCCEEDED);var retention=service();for(int i=4;i>=0;i--) retention.record(backup(i));
        retention.reconcile(policy(2,7));assertThat(deletions).isEmpty();
    }
    @Test void unknownWorkerOutcomeKeepsItsOperationIdAcrossRetries() throws Exception {
        workers(OperationStatus.RUNNING);var retention=service();retention.record(backup(10));retention.record(backup(8));retention.record(backup(6));
        retention.reconcile(policy(2,1));UUID op=deletions.getFirst().operationId();
        doThrow(new IllegalStateException("Docker unavailable")).when(workers).get(op);retention.reconcile(policy(2,1));
        assertThat(retention.health(policyId)).isEqualTo("ERROR");clock.now=clock.now.plusSeconds(31);
        doReturn(operation(op,OperationStatus.SUCCEEDED)).when(workers).get(op);retention=service();retention.reconcile(policy(2,1));
        assertThat(deletions).hasSize(1);assertThat(retention.health(policyId)).isEqualTo("OK");
    }
}
