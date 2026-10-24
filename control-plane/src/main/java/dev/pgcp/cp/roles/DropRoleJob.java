package dev.pgcp.cp.roles;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.databases.ManagedObjects;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;

/**
 * Drops a role only if it owns nothing. Remaining privileges are revoked with DROP OWNED in each affected database,
 * which is safe precisely because the ownership check found no owned objects.
 */
@Component
public class DropRoleJob implements JobHandler {

    public static final String TYPE = "role.drop";

    public record Params(UUID instanceId, String role) {
    }

    private final InstanceRepository instances;
    private final InstanceConnector connector;
    private final ManagedObjects objects;
    private final SecretStore secrets;

    public DropRoleJob(InstanceRepository instances, InstanceConnector connector, ManagedObjects objects, SecretStore secrets) {
        this.instances = instances;
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
        try (Connection c = connector.open(i, i.maintenanceDb(), InstanceConnector.As.ADMIN)) {
            if (PgAdmin.roleExists(c, p.role())) {
                List<PgAdmin.Dependency> deps = PgAdmin.dependencies(c, p.role());
                List<PgAdmin.Dependency> owned = deps.stream().filter(d -> d.kind().equals("OWNER")).toList();
                if (!owned.isEmpty()) {
                    throw new IllegalStateException("role " + p.role() + " still owns objects: " + owned
                            + ". Reassign or drop them first.");
                }
                Set<String> dbs = new TreeSet<>();
                deps.forEach(d -> dbs.add(d.database()));
                dbs.remove("(cluster)");
                dbs.add(i.maintenanceDb());
                for (String db : dbs) {
                    ctx.progress(20, "revoking privileges in " + db);
                    try (Connection inDb = connector.open(i, db, InstanceConnector.As.ADMIN)) {
                        PgAdmin.grantRoleToCurrentUser(inDb, p.role());
                        PgAdmin.dropOwnedPrivileges(inDb, p.role());
                    }
                }
                PgAdmin.terminateSessions(c, p.role());
                ctx.progress(80, "dropping role");
                PgAdmin.dropRole(c, p.role());
            } else {
                ctx.warn("role " + p.role() + " no longer exists");
            }
        }
        objects.findRole(i.id(), p.role()).ifPresent(m -> {
            objects.deleteRole(m.id());
            if (m.secretId() != null) {
                secrets.delete(m.secretId());
            }
        });
    }
}
