package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.AgentException;
import tools.jackson.databind.json.JsonMapper;

class BackupPolicyServiceTest {
    @TempDir Path data;
    final JsonMapper json=JsonMapper.builder().build();
    final LogicalWorkerService workers=mock(LogicalWorkerService.class);
    final MutableClock clock=new MutableClock(Instant.parse("2026-11-15T12:00:00Z"));
    final UUID policyId=UUID.randomUUID();
    final Map<UUID,Operation> operations=new HashMap<>();
    BackupPolicyService service() throws Exception { return new BackupPolicyService(data,workers,json,false,clock); }
    BackupPolicy policy(long revision,boolean enabled,String password) {
        var storage=new LogicalBackupRequest.Storage("https://storage.example","auto","backups","pgcp/test","access","secret","age1"+"q".repeat(58),null);
        var template=new LogicalBackupRequest(policyId,LogicalBackupRequest.Action.BACKUP,UUID.nameUUIDFromBytes(new byte[]{1}),"source","a".repeat(64),18,
                new LogicalBackupRequest.Connection("source",5432,"app","backup",password,"require",null),storage,
                storage.prefix()+"/"+policyId+".dump.age",storage.prefix()+"/"+policyId+".manifest.age",null,null);
        return new BackupPolicy(policyId,revision,UUID.nameUUIDFromBytes(new byte[]{2}),"02:30","Europe/Prague",enabled,true,26,template);
    }
    void worker() throws Exception {
        when(workers.get(any())).thenAnswer(i->{ Operation op=operations.get(i.getArgument(0));if(op==null) throw AgentException.notFound("missing");return op; });
        when(workers.start(any())).thenAnswer(i->{ LogicalBackupRequest req=i.getArgument(0);var op=new Operation(req.operationId(),OperationType.LOGICAL_BACKUP,"source",
                OperationStatus.RUNNING,0,"running",null,clock.instant(),null,Map.of());operations.put(op.id(),op);return op; });
    }
    void finish(UUID id) {
        operations.put(id,new Operation(id,OperationType.LOGICAL_BACKUP,"source",OperationStatus.SUCCEEDED,100,"done",null,operations.get(id).createdAt(),clock.instant(),
                Map.of("sha256","a".repeat(64),"artifactKey","pgcp/test/"+id+".dump.age","manifestKey","pgcp/test/"+id+".manifest.age")));
    }
    @Test void survivesAgentRestartAndPanelOutageWithoutDuplicatingRunsOrResults() throws Exception {
        worker();var first=service();first.put(policy(1,true,"source-password"));first.tick();
        UUID run=first.statuses().getFirst().activeRunId();assertThat(run).isNotNull();
        var restarted=service();restarted.tick();verify(workers,times(1)).start(any());
        finish(run);restarted.tick();
        assertThat(restarted.events()).hasSize(1);
        var again=service();again.tick();assertThat(again.events()).isEqualTo(restarted.events());
        assertThat(json.writeValueAsString(again.statuses())+json.writeValueAsString(again.events())).doesNotContain("source-password","access","secret");
        assertThat(Files.getPosixFilePermissions(data.resolve("protection/policies/"+policyId+".json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        again.acknowledge(again.events().getFirst().id());assertThat(service().events()).isEmpty();
        verify(workers,times(1)).start(any());
    }
    @Test void catchesUpOnceAfterLongOutageWithoutABacklog() throws Exception {
        worker();var service=service();service.put(policy(1,true,"password"));
        clock.now=clock.now.plus(Duration.ofDays(5));service.tick();
        UUID run=service.statuses().getFirst().activeRunId();finish(run);service.tick();service.tick();
        verify(workers,times(1)).start(any());
        assertThat(service.statuses().getFirst().nextRunAt()).isAfter(clock.instant());
        assertThat(service.events().getFirst().status()).isEqualTo("SUCCEEDED");
    }
    @Test void credentialRotationAndPausePreserveTheInFlightSnapshot() throws Exception {
        worker();var service=service();service.put(policy(1,true,"old-password"));service.tick();
        UUID run=service.statuses().getFirst().activeRunId();
        service.put(policy(2,false,"new-password"));
        assertThat(Files.readString(data.resolve("protection/policies/"+policyId+".json"))).contains("old-password","new-password");
        assertThatThrownBy(()->service.put(policy(2,false,"other-password"))).isInstanceOf(AgentException.class);
        finish(run);service.tick();
        assertThat(Files.readString(data.resolve("protection/policies/"+policyId+".json"))).doesNotContain("old-password");
        clock.now=clock.now.plus(Duration.ofDays(2));service.tick();verify(workers,times(1)).start(any());
        assertThat(service.statuses().getFirst().health()).isEqualTo("PAUSED");
        assertThat(service.events().getFirst().revision()).isEqualTo(1);
    }
    @Test void busyWorkerDefersThenFailsBoundedlyAndKeepsResultUntilAcknowledged() throws Exception {
        worker();doThrow(AgentException.conflict("busy")).when(workers).start(any());
        var service=service();service.put(policy(1,true,"password"));service.tick();
        assertThat(service.statuses().getFirst().activeRunId()).isNotNull();
        clock.now=clock.now.plusSeconds(7201);service.tick();
        assertThat(service.statuses().getFirst().health()).isEqualTo("BACKUP_FAILED");
        assertThat(service.events()).hasSize(1);assertThat(service.events().getFirst().status()).isEqualTo("FAILED");
        assertThat(service.statuses().getFirst().nextRunAt()).isAfter(clock.instant());
    }
    @Test void wallClockScheduleHandlesDstGapAndOverlapOncePerDay() {
        var p=policy(1,true,"password");
        assertThat(p.nextAfter(Instant.parse("2026-03-28T23:00:00Z"))).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
        assertThat(p.nextAfter(Instant.parse("2026-10-24T22:00:00Z"))).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(p.nextAfter(Instant.parse("2026-10-25T00:30:00Z"))).isEqualTo(Instant.parse("2026-10-26T01:30:00Z"));
    }
    @Test void delayedResultConsumptionDoesNotMakeAnOldSnapshotFresh() throws Exception {
        worker();var service=service();service.put(policy(1,true,"password"));service.tick();
        UUID run=service.statuses().getFirst().activeRunId();Instant started=clock.instant();finish(run);
        clock.now=clock.now.plus(Duration.ofDays(3));service.tick();
        assertThat(service.statuses().getFirst().lastSuccessAt()).isEqualTo(started);
        assertThat(service.statuses().getFirst().health()).isEqualTo("STALE");
        assertThat(service.events().getFirst().startedAt()).isEqualTo(started);
    }
    static class MutableClock extends Clock {
        Instant now;MutableClock(Instant now) { this.now=now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
