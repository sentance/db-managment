package dev.pgcp.cp.backups;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.pgcp.agent.api.*;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;
import dev.pgcp.cp.crypto.SecretStore;

@Service
public class BackupPolicyService {
    public record PolicyRequest(String name,UUID instanceId,String database,UUID destinationId,String containerId,
            String dailyTime,String timezone,boolean enabled,boolean runImmediately,int maxAgeHours) { }
    public record PolicyView(UUID id,String name,long revision,UUID instanceId,String database,UUID destinationId,
            String containerId,String dailyTime,String timezone,boolean enabled,int maxAgeHours,
            String syncState,BackupPolicyStatus execution,Instant checkedAt,String health) { }
    private final BackupPolicyRepository repo;
    private final LogicalBackupService logical;
    private final SecretStore secrets;
    private final Json json;
    private final StepUp stepUp;
    private final AuditService audit;
    private final AgentClient agent;
    private final boolean allowHttp;
    public BackupPolicyService(BackupPolicyRepository repo,LogicalBackupService logical,SecretStore secrets,Json json,
            StepUp stepUp,AuditService audit,AgentClient agent,@Value("${pgcp.backups.allow-http-storage:false}") boolean allowHttp) {
        this.repo=repo;this.logical=logical;this.secrets=secrets;this.json=json;this.stepUp=stepUp;this.audit=audit;this.agent=agent;this.allowHttp=allowHttp;
    }
    @Transactional
    public PolicyView create(PolicyRequest request) {
        stepUp.require();
        UUID id=UUID.randomUUID();
        var policy=policy(id,1,request);
        long secret=secrets.create("backup-policy",json.write(policy));
        repo.insert(id,request.name(),secret);
        audit.success("backup.policy.consent","backup_policy",id,Map.of("database",request.database(),"instanceId",request.instanceId().toString()));
        return view(repo.require(id)); // Sync after commit in coordinator; do not claim an unacknowledged schedule is active.
    }
    @Transactional
    public PolicyView update(UUID id,PolicyRequest request) {
        stepUp.require();
        var row=repo.lock(id);
        var old=read(row);
        if(!old.template().instanceId().equals(request.instanceId()) || !old.template().connection().database().equals(request.database())
                || !old.destinationId().equals(request.destinationId())) throw ApiException.badRequest("create a new policy when changing the database, instance or storage destination");
        var policy=policy(id,row.revision()+1,request);
        secrets.replace(row.templateSecretId(),json.write(policy));
        repo.revision(id,request.name(),policy.revision());
        audit.success("backup.policy.update","backup_policy",id,Map.of("revision",policy.revision(),"enabled",policy.enabled()));
        return view(repo.require(id));
    }
    @Transactional
    public PolicyView enabled(UUID id,boolean enabled) {
        stepUp.require();
        var row=repo.lock(id);var old=read(row);
        var policy=new BackupPolicy(id,row.revision()+1,old.destinationId(),old.dailyTime(),old.timezone(),enabled,false,old.maxAgeHours(),old.template());
        secrets.replace(row.templateSecretId(),json.write(policy));repo.revision(id,row.name(),policy.revision());
        audit.success("backup.policy.enabled","backup_policy",id,Map.of("enabled",enabled));
        return view(repo.require(id));
    }
    private BackupPolicy policy(UUID id,long revision,PolicyRequest request) {
        if(request.name()==null || request.name().isBlank() || request.name().length()>100 || request.instanceId()==null || request.destinationId()==null) {
            throw ApiException.badRequest("policy name, source and destination are required");
        }
        var template=logical.consentedTemplate(request.instanceId(),new LogicalBackupService.BackupRequest(request.database(),request.destinationId(),request.containerId()),id);
        var policy=new BackupPolicy(id,revision,request.destinationId(),request.dailyTime(),request.timezone(),request.enabled(),request.runImmediately(),request.maxAgeHours(),template);
        try { policy.validate(allowHttp); } catch(RuntimeException e) { throw ApiException.badRequest("select a valid daily time, timezone and maximum age (24–8760 hours)"); }
        return policy;
    }
    private BackupPolicy read(BackupPolicyRepository.Row row) { return json.read(secrets.read(row.templateSecretId()),BackupPolicy.class); }
    public List<PolicyView> list() { return repo.list().stream().map(this::view).toList(); }
    private PolicyView view(BackupPolicyRepository.Row row) {
        var policy=read(row);
        boolean fresh=row.checkedAt()!=null && row.checkedAt().plusSeconds(120).isAfter(Instant.now()) && row.syncError()==null;
        boolean synced=Objects.equals(row.appliedRevision(),row.revision());
        String sync=synced && !fresh?"UNCONFIRMED":synced?"APPLIED":"PENDING";
        String health=!fresh?"UNKNOWN":!synced?"SYNC_PENDING":row.agentStatus()==null?"UNKNOWN":row.agentStatus().health();
        return new PolicyView(row.id(),row.name(),row.revision(),policy.template().instanceId(),policy.template().connection().database(),
                policy.destinationId(),policy.template().containerId(),policy.dailyTime(),policy.timezone(),policy.enabled(),policy.maxAgeHours(),
                sync,row.agentStatus(),row.checkedAt(),health);
    }

    @Scheduled(fixedDelayString="${pgcp.protection.poll-interval:10s}",initialDelayString="${pgcp.protection.initial-delay:5s}")
    public synchronized void reconcile() {
        var rows=repo.list();
        if(rows.isEmpty()) return;
        Map<UUID,BackupPolicyStatus> observed=new HashMap<>();
        try { for(var status:agent.backupPolicies()) observed.put(status.id(),status); }
        catch(RuntimeException e) { for(var row:rows) repo.unavailable(row.id());return; }
        for(var row:rows) {
            var status=observed.get(row.id());
            if(status==null || status.revision()!=row.revision()) {
                try { repo.observed(agent.backupPolicy(read(row))); }
                catch(RuntimeException e) { repo.unavailable(row.id()); }
            } else repo.observed(status);
        }
        try {
            for(var event:agent.backupEvents()) {
                try { repo.ingest(event);agent.acknowledgeBackupEvent(event.id()); }
                catch(RuntimeException ignored) { /* Leave this event durable; other valid events can still advance. */ }
            }
        } catch(RuntimeException e) { for(var row:rows) repo.unavailable(row.id()); }
    }
}
