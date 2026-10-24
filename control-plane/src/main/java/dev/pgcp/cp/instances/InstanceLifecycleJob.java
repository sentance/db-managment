package dev.pgcp.cp.instances;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.pgcp.agent.api.InstanceLifecycleRequest;
import dev.pgcp.agent.api.LifecycleAction;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;

@Component
public class InstanceLifecycleJob implements JobHandler {

    public static final String TYPE = "instance.lifecycle";

    public record Params(UUID instanceId, LifecycleAction action) {
    }

    private final InstanceRepository repo;
    private final AgentClient agent;

    public InstanceLifecycleJob(InstanceRepository repo, AgentClient agent) {
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
        var op = agent.lifecycle(i.name(), new InstanceLifecycleRequest(AgentClient.operationId(ctx.jobId(), "lifecycle"),
                i.id(), p.action()));
        agent.await(op, ctx, 5, 95);
        repo.updateStatus(i.id(), p.action() == LifecycleAction.STOP ? Instance.STOPPED : Instance.RUNNING, null);
    }
}
