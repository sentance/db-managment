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

/** Renames a database. PostgreSQL requires that nobody is connected; we never force this. */
@Component
public class RenameDatabaseJob implements JobHandler {

    public static final String TYPE = "database.rename";

    public record Params(UUID instanceId, String from, String to) {
    }

    private final InstanceRepository instances;
    private final InstanceConnector connector;
    private final ManagedObjects objects;

    public RenameDatabaseJob(InstanceRepository instances, InstanceConnector connector, ManagedObjects objects) {
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
            if (!PgAdmin.databaseExists(c, p.from()) && PgAdmin.databaseExists(c, p.to())) {
                ctx.warn("already renamed");
            } else {
                if (PgAdmin.databaseExists(c, p.to())) {
                    throw new IllegalStateException("database " + p.to() + " already exists");
                }
                int sessions = PgAdmin.connectionCount(c, p.from());
                if (sessions > 0) {
                    throw new IllegalStateException(sessions + " client(s) are connected to " + p.from()
                            + "; renaming requires no connections");
                }
                PgAdmin.renameDatabase(c, p.from(), p.to());
            }
        }
        objects.renameDatabase(i.id(), p.from(), p.to());
        ctx.info("connection strings of applications must be updated to the new name");
    }
}
