package dev.pgcp.cp.backups;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import dev.pgcp.agent.api.*;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.common.Json;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.instances.*;
import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;
import dev.pgcp.cp.pg.PgIdent;

@Service
public class LogicalBackupService {
    public record DestinationRequest(String name, String endpoint, String region, String bucket, String accessKey, String secretKey) {
        @Override public String toString() { return "DestinationRequest[redacted]"; }
    }
    public record DestinationView(UUID id, String name, String endpoint, String region, String bucket, String prefix,
            String recipient, String status, java.time.Instant testedAt) { }
    public record DestinationCreated(DestinationView destination, String recoveryIdentity) {
        @Override public String toString() { return "DestinationCreated[recoveryIdentity=redacted]"; }
    }
    public record BackupRequest(String database, UUID destinationId, String containerId) { }
    public record RestoreRequest(String instanceName, String database, String owner) { }
    public record Submitted(UUID id, UUID jobId) { }
    public record ItemParams(UUID id) { }
    private final LogicalRepository repo;
    private final InstanceRepository instances;
    private final InstanceService instanceService;
    private final InstanceConnector connector;
    private final SecretStore secrets;
    private final AgentClient agent;
    private final JobService jobs;
    private final StepUp stepUp;
    private final AuditService audit;
    private final Json json;
    private final boolean allowHttp;

    public LogicalBackupService(LogicalRepository repo, InstanceRepository instances, InstanceService instanceService,
            InstanceConnector connector, SecretStore secrets, AgentClient agent, JobService jobs, StepUp stepUp,
            AuditService audit, Json json, @Value("${pgcp.backups.allow-http-storage:false}") boolean allowHttp) {
        this.repo=repo; this.instances=instances; this.instanceService=instanceService; this.connector=connector;
        this.secrets=secrets; this.agent=agent; this.jobs=jobs; this.stepUp=stepUp; this.audit=audit; this.json=json; this.allowHttp=allowHttp;
    }
    public List<DestinationView> destinations() { return repo.destinations().stream().map(LogicalBackupService::view).toList(); }
    private static DestinationView view(LogicalRepository.Destination d) {
        return new DestinationView(d.id(),d.name(),d.endpoint(),d.region(),d.bucket(),d.prefix(),d.recipient(),d.status(),d.testedAt());
    }

    @Transactional
    public DestinationCreated createDestination(DestinationRequest request) {
        stepUp.require();
        if (request.name()==null || request.name().isBlank() || request.name().length()>100) throw ApiException.badRequest("destination name is required (maximum 100 characters)");
        UUID id=UUID.randomUUID();
        String endpoint=request.endpoint()==null?"":request.endpoint();
        String prefix="pgcp/"+id;
        try { new LogicalBackupRequest.Storage(endpoint,request.region(),request.bucket(),prefix,request.accessKey(),request.secretKey(),"age1"+"q".repeat(58),null).validate(allowHttp); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest(e.getMessage()); }
        RecoveryKey key=agent.recoveryKey();
        if (key==null || key.identity()==null || !key.identity().startsWith("AGE-SECRET-KEY-1")) throw ApiException.unavailable("recovery key generation failed");
        long credentials=secrets.create("backup-storage",json.write(Map.of("accessKey",request.accessKey(),"secretKey",request.secretKey())));
        long identity=secrets.create("backup-recovery-key",key.identity());
        var destination=new LogicalRepository.Destination(id,request.name(),endpoint,request.region(),request.bucket(),prefix,key.recipient(),credentials,identity,"UNTESTED",null);
        repo.destination(destination);
        audit.success("backup.destination.create","backup_destination",id,Map.of("name",request.name()));
        return new DestinationCreated(view(destination),key.identity());
    }

    @Transactional
    public JobService.Submitted testDestination(UUID id) {
        stepUp.require(); repo.destination(id);
        return new JobService.Submitted(jobs.submit("backup.destination.test","backup-destination:"+id,"Test backup destination",new ItemParams(id)));
    }

    @Transactional
    public Submitted backup(UUID instanceId, BackupRequest request) {
        stepUp.require();
        UUID id=UUID.randomUUID();
        LogicalBackupRequest operation=consentedTemplate(instanceId,request,id);
        Instance source=instances.require(instanceId);
        repo.insertBackup(id,instanceId,request.database(),request.destinationId(),operation.containerId(),operation.pgMajor());
        UUID job=jobs.submit("backup.logical",source.resourceKey(),"Back up "+source.name()+" / "+request.database(),new ItemParams(id));
        repo.backupJob(id,job);
        audit.success("backup.logical.consent","instance",instanceId,Map.of("database",request.database(),"backupId",id.toString()));
        return new Submitted(id,job);
    }

    /** Called only by step-up protected export/policy consent paths. */
    public LogicalBackupRequest consentedTemplate(UUID instanceId, BackupRequest request, UUID operationId) {
        Instance source=instances.require(instanceId);
        var destination=repo.destination(request.destinationId());
        if (!"VERIFIED".equals(destination.status())) throw ApiException.badRequest("test the destination before creating a backup");
        if (source.containerName()==null) throw ApiException.badRequest("logical backup currently requires a registered Docker source");
        var container=agent.postgresContainers().stream().filter(c->c.name().equals(source.containerName())).findFirst()
                .orElseThrow(()->ApiException.notFound("source container"));
        if (!container.id().equals(request.containerId()) || container.pgMajor()==null) throw ApiException.conflict("source container changed; review discovery and confirm export again");
        try { AgentApi.requireSupportedMajor(container.pgMajor()); }
        catch (IllegalArgumentException e) { throw ApiException.badRequest("this PostgreSQL major is not supported for logical backup"); }
        if (request.database()==null || request.database().isEmpty() || request.database().length()>63
                || !connector.withMaintenance(source,c->PgAdmin.databaseExists(c,request.database()))) throw ApiException.badRequest("select an existing source database");
        return backupOperation(new LogicalRepository.Backup(operationId,instanceId,request.database(),request.destinationId(),
                container.id(),container.pgMajor(),"PENDING",null,Map.of(),null,java.time.Instant.now()));
    }

    @Transactional
    public Submitted restore(UUID backupId, RestoreRequest request) {
        stepUp.require();
        var backup=repo.backup(backupId);
        if (!"SUCCEEDED".equals(backup.status())) throw ApiException.badRequest("only a successful backup can be restored");
        try { AgentApi.requireValidInstanceName(request.instanceName()); } catch (IllegalArgumentException e) { throw ApiException.badRequest("invalid destination instance name"); }
        PgIdent.requireNewName(request.database(),"database");
        PgIdent.requireNewName(request.owner(),"owner role");
        if (instances.nameTaken(request.instanceName())) throw ApiException.conflict("restore requires a new instance name");
        UUID id=UUID.randomUUID();
        repo.insertRestore(id,backupId,request.instanceName(),request.database(),request.owner());
        UUID job=jobs.submit("backup.restore","restore-name:"+request.instanceName(),"Restore backup into "+request.instanceName(),new ItemParams(id));
        repo.restoreJob(id,job);
        return new Submitted(id,job);
    }

    @Transactional
    public UUID prepareRestore(UUID restoreId) {
        var restore=repo.restore(restoreId);
        if (restore.targetInstanceId()!=null) return restore.targetInstanceId();
        var backup=repo.backup(restore.backupId());
        var request=new InstanceService.CreateManagedRequest(restore.targetName(),backup.pgMajor(),512,1.0,
                Instance.Protection.EPHEMERAL,InstanceService.Exposure.NONE,null,List.of(),Map.of(),
                new InstanceService.InitialDatabase(restore.databaseName(),restore.ownerRole(),false));
        var result=instanceService.createManagedForRestore(request,restoreId);
        instances.isolatedNetwork(result.instance().id(),"pgcp-restore-"+restoreId);
        repo.restoreTarget(restoreId,result.instance().id());
        return result.instance().id();
    }

    public LogicalBackupRequest.Storage storage(UUID destinationId, boolean recovery) {
        var d=repo.destination(destinationId);
        Map<String,Object> credentials=json.readMap(secrets.read(d.credentialsSecretId()));
        return new LogicalBackupRequest.Storage(d.endpoint(),d.region(),d.bucket(),d.prefix(),
                (String)credentials.get("accessKey"),(String)credentials.get("secretKey"),d.recipient(),
                recovery?secrets.read(d.identitySecretId()):null);
    }

    public LogicalBackupRequest backupOperation(LogicalRepository.Backup backup) {
        var source=instances.require(backup.instanceId());
        var storage=storage(backup.destinationId(),false);
        return new LogicalBackupRequest(backup.id(),LogicalBackupRequest.Action.BACKUP,source.id(),source.containerName(),backup.containerId(),backup.pgMajor(),
                new LogicalBackupRequest.Connection(source.containerName(),5432,backup.databaseName(),source.adminRole(),
                        source.adminSecretId()==null?"":secrets.read(source.adminSecretId()),source.sslMode(),null),storage,
                storage.prefix()+"/"+backup.id()+".dump.age",storage.prefix()+"/"+backup.id()+".manifest.age",null,null);
    }

    public LogicalBackupRequest restoreOperation(LogicalRepository.Restore restore) {
        var backup=repo.backup(restore.backupId());
        var target=instances.require(restore.targetInstanceId());
        ContainerInfo info=agent.managedInstance(target.name());
        return new LogicalBackupRequest(restore.id(),LogicalBackupRequest.Action.RESTORE,target.id(),target.containerName(),info.id(),backup.pgMajor(),
                new LogicalBackupRequest.Connection(target.containerName(),5432,restore.databaseName(),"postgres",secrets.read(target.superuserSecretId()),"require",null),
                storage(backup.destinationId(),true),(String)backup.result().get("artifactKey"),(String)backup.result().get("manifestKey"),
                (String)backup.result().get("sha256"),restore.ownerRole());
    }
}
