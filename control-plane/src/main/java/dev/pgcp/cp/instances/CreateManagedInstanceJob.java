package dev.pgcp.cp.instances;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.pgcp.agent.api.CreateInstanceRequest;
import dev.pgcp.agent.api.InstanceSpec;
import dev.pgcp.agent.api.Operation;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.config.CpProperties;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.databases.DatabaseProvisioner;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;

/** Provisions a managed instance through the agent, bootstraps the admin role and optionally a first database. */
@Component
public class CreateManagedInstanceJob implements JobHandler {

    public static final String TYPE = "instance.create";

    public record InitialDb(String name, String owner, String readOnlyRole) {
    }

    public record Params(UUID instanceId, InitialDb initialDatabase) {
    }

    private final InstanceRepository repo;
    private final AgentClient agent;
    private final SecretStore secrets;
    private final InstanceConnector connector;
    private final DatabaseProvisioner provisioner;
    private final CpProperties props;

    public CreateManagedInstanceJob(InstanceRepository repo, AgentClient agent, SecretStore secrets,
            InstanceConnector connector, DatabaseProvisioner provisioner, CpProperties props) {
        this.repo = repo;
        this.agent = agent;
        this.secrets = secrets;
        this.connector = connector;
        this.provisioner = provisioner;
        this.props = props;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        Params p = ctx.params(Params.class);
        Instance i = repo.require(p.instanceId());
        repo.updateStatus(i.id(), Instance.PROVISIONING, "starting container");

        List<String> tlsNames = new ArrayList<>();
        if (props.publicHost() != null && !props.publicHost().isBlank()) {
            tlsNames.add(props.publicHost());
        }
        var spec = new InstanceSpec(i.id(), i.name(), i.pgMajor(), i.memoryMb(), i.cpus().doubleValue(),
                Math.max(64, Math.min(1024, i.memoryMb() / 4)), i.settings(), i.bindAddress(), i.hostPort(),
                i.extraNetworks(), secrets.read(i.superuserSecretId()), tlsNames);
        ctx.progress(5, "asking the agent to create the container");
        Operation op = agent.createInstance(new CreateInstanceRequest(AgentClient.operationId(ctx.jobId(), "create"), spec));
        op = agent.await(op, ctx, 5, 70);
        Map<String, String> r = op.result();
        repo.updateProvisioned(i.id(), r.get("containerName"), r.get("image"), r.get("libc"), r.get("internalHost"),
                Integer.parseInt(r.get("internalPort")));
        i = repo.require(i.id());

        ctx.progress(75, "creating technical role " + i.adminRole());
        String version = bootstrapAdminRole(i);
        repo.updateVersion(i.id(), version);

        if (p.initialDatabase() != null) {
            var db = p.initialDatabase();
            provisioner.provision(i, new DatabaseProvisioner.DbSpec(db.name(), db.owner(), db.readOnlyRole()), ctx);
        }
        repo.updateStatus(i.id(), Instance.RUNNING, null);
        ctx.result(Map.of("instanceId", i.id().toString(), "container", r.get("containerName")));
    }

    /** pgcp_admin: CREATEDB + CREATEROLE + monitoring, but not superuser. Routine operations use this role. */
    private String bootstrapAdminRole(Instance i) throws SQLException, InterruptedException {
        SQLException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try (Connection c = connector.open(i, "postgres", InstanceConnector.As.SUPERUSER)) {
                PgAdmin.ensureAdminRole(c, i.adminRole(), secrets.read(i.adminSecretId()));
                return PgAdmin.serverVersion(c);
            } catch (SQLException e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        throw last;
    }

    @Override
    public void onFailure(JobContext ctx, Exception error) {
        Params p = ctx.params(Params.class);
        repo.updateStatus(p.instanceId(), Instance.FAILED, error.getMessage());
    }
}
