package dev.pgcp.cp.databases;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;

@Component
public class CreateDatabaseJob implements JobHandler {

    public static final String TYPE = "database.create";

    public record Params(UUID instanceId, String database, String owner, String readOnlyRole) {
    }

    private final InstanceRepository instances;
    private final DatabaseProvisioner provisioner;
    private final InstanceConnector connector;
    private final ManagedObjects objects;
    private final SecretStore secrets;

    public CreateDatabaseJob(InstanceRepository instances, DatabaseProvisioner provisioner, InstanceConnector connector,
            ManagedObjects objects, SecretStore secrets) {
        this.instances = instances;
        this.provisioner = provisioner;
        this.connector = connector;
        this.objects = objects;
        this.secrets = secrets;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void run(JobContext ctx) throws Exception {
        Params p = ctx.params(Params.class);
        Instance i = instances.require(p.instanceId());
        provisioner.provision(i, new DatabaseProvisioner.DbSpec(p.database(), p.owner(), p.readOnlyRole()), ctx);
        ctx.result(Map.of("database", p.database(), "owner", p.owner()));
    }

    /** Forget roles that were registered for this job but never created, so their names can be reused. */
    @Override
    public void onFailure(JobContext ctx, Exception error) {
        Params p = ctx.params(Params.class);
        Instance i = instances.require(p.instanceId());
        List<String> roles = new ArrayList<>(List.of(p.owner()));
        if (p.readOnlyRole() != null) {
            roles.add(p.readOnlyRole());
        }
        try (Connection c = connector.open(i, i.maintenanceDb(), InstanceConnector.As.ADMIN)) {
            for (String role : roles) {
                if (!PgAdmin.roleExists(c, role)) {
                    objects.findRole(i.id(), role).ifPresent(m -> {
                        objects.deleteRole(m.id());
                        if (m.secretId() != null) {
                            secrets.delete(m.secretId());
                        }
                    });
                }
            }
        } catch (Exception e) {
            ctx.warn("cleanup skipped: " + e.getMessage());
        }
    }
}
