package dev.pgcp.cp.instances;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.pgcp.agent.api.DeleteInstanceRequest;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;

@Component
public class DeleteInstanceJob implements JobHandler {

    public static final String TYPE = "instance.delete";

    public record Params(UUID instanceId, boolean deleteVolume) {
    }

    private final InstanceRepository repo;
    private final AgentClient agent;

    public DeleteInstanceJob(InstanceRepository repo, AgentClient agent) {
        this.repo = repo;
        this.agent = agent;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        Params p = ctx.params(Params.class);
        Instance i = repo.require(p.instanceId());
        repo.updateStatus(i.id(), Instance.DELETING, null);
        var op = agent.deleteInstance(i.name(), new DeleteInstanceRequest(AgentClient.operationId(ctx.jobId(), "delete"),
                i.id(), p.deleteVolume()));
        op = agent.await(op, ctx, 5, 95);
        ctx.result(op.result());
        repo.updateStatus(i.id(), Instance.DELETED, p.deleteVolume() ? "data volume removed" : "data volume kept: " + op.result().get("volume"));
    }

    @Override
    public void onFailure(JobContext ctx, Exception error) {
        repo.updateStatus(ctx.params(Params.class).instanceId(), Instance.FAILED, "delete failed: " + error.getMessage());
    }
}
