package dev.pgcp.cp.backups;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import dev.pgcp.agent.api.LogicalBackupRequest;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.backups.LogicalBackupService.ItemParams;
import dev.pgcp.cp.common.Json;
import dev.pgcp.cp.instances.CreateManagedInstanceJob;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;

public final class LogicalJobs {
    private LogicalJobs() { }

    @Component
    public static class StorageTest implements JobHandler {
        private final LogicalRepository repo; private final LogicalBackupService service; private final AgentClient agent;
        public StorageTest(LogicalRepository repo, LogicalBackupService service, AgentClient agent) { this.repo=repo;this.service=service;this.agent=agent; }
        public String type() { return "backup.destination.test"; }
        public void run(JobContext ctx) throws Exception {
            UUID id=ctx.params(ItemParams.class).id();
            agent.logical(new LogicalBackupRequest(ctx.jobId(),LogicalBackupRequest.Action.TEST_STORAGE,id,null,null,18,null,
                    service.storage(id,false),null,null,null,null),ctx);
            repo.destinationStatus(id,true);
        }
        public void onFailure(JobContext ctx, Exception error) { repo.destinationStatus(ctx.params(ItemParams.class).id(),false); }
    }

    @Component
    public static class Export implements JobHandler {
        private final LogicalRepository repo; private final LogicalBackupService service; private final AgentClient agent;
        public Export(LogicalRepository repo, LogicalBackupService service, AgentClient agent) { this.repo=repo;this.service=service;this.agent=agent; }
        public String type() { return "backup.logical"; }
        public void run(JobContext ctx) throws Exception {
            UUID id=ctx.params(ItemParams.class).id();
            repo.backupStatus(id,"RUNNING",null);
            var op=agent.logical(service.backupOperation(repo.backup(id)),ctx);
            if (op.result().get("sha256")==null || !op.result().get("sha256").matches("[a-f0-9]{64}")
                    || op.result().get("artifactKey")==null || op.result().get("manifestKey")==null) {
                throw new IllegalStateException("worker returned incomplete backup metadata");
            }
            repo.backupStatus(id,"SUCCEEDED",op.result());
            ctx.result(Map.of("backupId",id.toString(),"encrypted",true));
        }
        public void onFailure(JobContext ctx, Exception error) { repo.backupStatus(ctx.params(ItemParams.class).id(),"FAILED",null); }
    }

    @Component
    public static class Restore implements JobHandler {
        private final LogicalRepository repo; private final LogicalBackupService service; private final AgentClient agent;
        private final InstanceRepository instances; private final CreateManagedInstanceJob provisioner; private final Json json;
        public Restore(LogicalRepository repo, LogicalBackupService service, AgentClient agent, InstanceRepository instances,
                CreateManagedInstanceJob provisioner, Json json) { this.repo=repo;this.service=service;this.agent=agent;this.instances=instances;this.provisioner=provisioner;this.json=json; }
        public String type() { return "backup.restore"; }
        public void run(JobContext ctx) throws Exception {
            UUID id=ctx.params(ItemParams.class).id();
            repo.restoreStatus(id,"RUNNING",null);
            UUID target=service.prepareRestore(id);
            var restore=repo.restore(id);
            if (Instance.PROVISIONING.equals(instances.require(target).status())) {
                var params=new CreateManagedInstanceJob.Params(target,new CreateManagedInstanceJob.InitialDb(restore.databaseName(),restore.ownerRole(),null),id);
                provisioner.run(new JobContext() {
                    public UUID jobId() { return ctx.jobId(); }
                    public <T> T params(Class<T> type) { return json.convert(params,type); }
                    public void info(String m) { ctx.info(m); }
                    public void warn(String m) { ctx.warn(m); }
                    public void progress(int p,String m) { ctx.progress(p/3,m); }
                    public void result(Map<String,?> r) { }
                });
            }
            instances.updateStatus(target,"RESTORING","restoring into isolated destination");
            var op=agent.logical(service.restoreOperation(repo.restore(id)),ctx);
            if (!"true".equals(op.result().get("verified"))) throw new IllegalStateException("restore verification did not succeed");
            repo.restoreStatus(id,"SUCCEEDED",op.result());
            repo.verified(restore.backupId());
            instances.updateStatus(target,Instance.RUNNING,null);
            ctx.result(Map.of("restoreId",id.toString(),"instanceId",target.toString(),"verified",true));
        }
        public void onFailure(JobContext ctx, Exception error) {
            UUID id=ctx.params(ItemParams.class).id();
            repo.restoreStatus(id,"FAILED",null);
            var target=repo.restore(id).targetInstanceId();
            if(target!=null) instances.updateStatus(target,Instance.FAILED,"restore failed; isolated destination retained for inspection");
        }
    }
}
