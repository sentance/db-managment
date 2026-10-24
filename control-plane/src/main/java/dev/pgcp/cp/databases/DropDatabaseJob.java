package dev.pgcp.cp.databases;

import java.sql.Connection;
import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.jobs.JobHandler;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;

/** Drops a database. Refuses while clients are connected unless the caller explicitly asked to terminate them. */
@Component
public class DropDatabaseJob implements JobHandler {

    public static final String TYPE = "database.drop";

    public record Params(UUID instanceId, String database, boolean terminateConnections) {
    }

    private final InstanceRepository instances;
    private final InstanceConnector connector;
    private final ManagedObjects objects;

    public DropDatabaseJob(InstanceRepository instances, InstanceConnector connector, ManagedObjects objects) {
        this.instances = instances;
        this.connector = connector;
        this.objects = objects;
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
            if (PgAdmin.databaseExists(c, p.database())) {
                int sessions = PgAdmin.connectionCount(c, p.database());
                if (sessions > 0 && !p.terminateConnections()) {
                    throw new IllegalStateException(sessions + " client(s) are connected to " + p.database()
                            + "; disconnect them or confirm terminating connections");
                }
                ctx.progress(30, "dropping database " + p.database() + (sessions > 0 ? " (terminating " + sessions + " sessions)" : ""));
                PgAdmin.dropDatabase(c, p.database(), p.terminateConnections());
            } else {
                ctx.warn("database " + p.database() + " no longer exists");
            }
        }
        objects.deleteDatabase(i.id(), p.database());
        ctx.info("roles were kept; remove them separately under Users & Permissions if no longer needed");
    }
}
