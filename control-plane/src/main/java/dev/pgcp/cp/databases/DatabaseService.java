package dev.pgcp.cp.databases;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.crypto.Passwords;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.instances.InstanceService.IssuedCredential;
import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;
import dev.pgcp.cp.pg.PgIdent;

@Service
public class DatabaseService {

    public record DatabaseView(String name, String owner, long sizeBytes, int connections, boolean allowConnections,
            String encoding, String collation, boolean managed, boolean system) {
    }

    public record DatabaseDetails(DatabaseView database, List<PgAdmin.Extension> extensions, List<String> loginRoles,
            String extensionsError) {
    }

    public record CreateRequest(String name, String owner, Boolean createReadOnlyRole) {
    }

    public record CreateResult(UUID jobId, String database, List<IssuedCredential> credentials,
            ConnectionInfoService.ConnectionInfo connection) {
    }

    private static final Set<String> SYSTEM_DBS = Set.of("postgres", "template0", "template1");

    private final InstanceRepository instances;
    private final InstanceConnector connector;
    private final ManagedObjects objects;
    private final SecretStore secrets;
    private final JobService jobs;
    private final StepUp stepUp;
    private final AuditService audit;
    private final ConnectionInfoService connections;

    public DatabaseService(InstanceRepository instances, InstanceConnector connector, ManagedObjects objects,
            SecretStore secrets, JobService jobs, StepUp stepUp, AuditService audit, ConnectionInfoService connections) {
        this.instances = instances;
        this.connector = connector;
        this.objects = objects;
        this.secrets = secrets;
        this.jobs = jobs;
        this.stepUp = stepUp;
        this.audit = audit;
        this.connections = connections;
    }

    public List<DatabaseView> list(UUID instanceId) {
        Instance i = instances.require(instanceId);
        Set<String> managed = objects.databases(instanceId).stream().map(ManagedObjects.ManagedDatabase::name)
                .collect(Collectors.toSet());
        return connector.withMaintenance(i, PgAdmin::databases).stream()
                .map(d -> view(d, managed.contains(d.name()), i)).toList();
    }

    public DatabaseDetails details(UUID instanceId, String name) {
        Instance i = instances.require(instanceId);
        var info = connector.withMaintenance(i, PgAdmin::databases).stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> ApiException.notFound("database"));
        List<String> loginRoles = connector.withMaintenance(i, c -> PgAdmin.loginRolesWithConnect(c, name));
        List<PgAdmin.Extension> extensions = List.of();
        String extError = null;
        if (info.allowConnections()) {
            try {
                extensions = connector.with(i, name, InstanceConnector.As.ADMIN, PgAdmin::extensions);
            } catch (ApiException e) {
                extError = e.getMessage();
            }
        }
        return new DatabaseDetails(view(info, objects.findDatabase(instanceId, name).isPresent(), i), extensions, loginRoles,
                extError);
    }

    @Transactional
    public CreateResult create(UUID instanceId, CreateRequest req) {
        Instance i = requireWritable(instanceId);
        String db = PgIdent.requireNewName(req.name(), "database");
        String owner = PgIdent.requireNewName(blank(req.owner()) ? db + "_owner" : req.owner(), "owner role");
        String readOnly = Boolean.TRUE.equals(req.createReadOnlyRole()) ? PgIdent.requireNewName(db + "_ro", "read-only role")
                : null;
        connector.withMaintenance(i, c -> {
            if (PgAdmin.databaseExists(c, db)) {
                throw ApiException.conflict("database " + db + " already exists");
            }
            for (String role : readOnly == null ? List.of(owner) : List.of(owner, readOnly)) {
                if (PgAdmin.roleExists(c, role)) {
                    throw ApiException.conflict("role " + role + " already exists; choose another name");
                }
            }
            return null;
        });
        List<IssuedCredential> creds = new ArrayList<>();
        String ownerPassword = Passwords.generate();
        objects.insertRole(instanceId, owner, ManagedObjects.RoleKind.OWNER, secrets.create("pg-role", ownerPassword));
        creds.add(new IssuedCredential(owner, "OWNER", ownerPassword));
        if (readOnly != null) {
            String roPassword = Passwords.generate();
            objects.insertRole(instanceId, readOnly, ManagedObjects.RoleKind.READ_ONLY, secrets.create("pg-role", roPassword));
            creds.add(new IssuedCredential(readOnly, "READ_ONLY", roPassword));
        }
        UUID jobId = jobs.submit(CreateDatabaseJob.TYPE, i.resourceKey(), "Create database " + db + " on " + i.name(),
                new CreateDatabaseJob.Params(instanceId, db, owner, readOnly));
        audit.success("database.create", "database", i.name() + "/" + db, Map.of("owner", owner,
                "readOnlyRole", String.valueOf(readOnly)));
        return new CreateResult(jobId, db, creds, connections.describe(i, db, owner, true, ownerPassword));
    }

    public UUID drop(UUID instanceId, String name, String confirmName, boolean terminateConnections) {
        Instance i = requireWritable(instanceId);
        stepUp.require();
        if (!name.equals(confirmName)) {
            throw ApiException.badRequest("type the database name to confirm");
        }
        if (SYSTEM_DBS.contains(name) || name.equals(i.maintenanceDb())) {
            throw ApiException.forbidden("system databases cannot be dropped");
        }
        UUID jobId = jobs.submit(DropDatabaseJob.TYPE, i.resourceKey(), "Drop database " + name + " on " + i.name(),
                new DropDatabaseJob.Params(instanceId, name, terminateConnections));
        audit.success("database.drop", "database", i.name() + "/" + name, Map.of("terminateConnections", terminateConnections,
                "protection", i.protection().name()));
        return jobId;
    }

    public UUID rename(UUID instanceId, String name, String newName) {
        Instance i = requireWritable(instanceId);
        if (SYSTEM_DBS.contains(name) || name.equals(i.maintenanceDb())) {
            throw ApiException.forbidden("system databases cannot be renamed");
        }
        String target = PgIdent.requireNewName(newName, "database");
        if (i.protection() == Instance.Protection.PRODUCTION) {
            stepUp.require();
        }
        UUID jobId = jobs.submit(RenameDatabaseJob.TYPE, i.resourceKey(), "Rename database " + name + " to " + target,
                new RenameDatabaseJob.Params(instanceId, name, target));
        audit.success("database.rename", "database", i.name() + "/" + name, Map.of("to", target));
        return jobId;
    }

    /** Connection parameters for a role (default: the database owner). The password is always masked here. */
    public ConnectionInfoService.ConnectionInfo connection(UUID instanceId, String db, String role) {
        Instance i = instances.require(instanceId);
        String r = role;
        if (blank(r)) {
            r = connector.withMaintenance(i, PgAdmin::databases).stream().filter(d -> d.name().equals(db)).findFirst()
                    .map(PgAdmin.DatabaseInfo::owner).orElseThrow(() -> ApiException.notFound("database"));
        }
        boolean stored = objects.findRole(instanceId, r).map(m -> m.secretId() != null).orElse(false);
        return connections.describe(i, db, r, stored, null);
    }

    Instance requireWritable(UUID instanceId) {
        Instance i = instances.require(instanceId);
        if (!i.objectsWritable()) {
            throw ApiException.forbidden("instance " + i.name() + " is registered in MONITOR_ONLY mode");
        }
        if (!Instance.RUNNING.equals(i.status())) {
            throw ApiException.conflict("instance " + i.name() + " is " + i.status());
        }
        return i;
    }

    private static DatabaseView view(PgAdmin.DatabaseInfo d, boolean managed, Instance i) {
        return new DatabaseView(d.name(), d.owner(), d.sizeBytes(), d.connections(), d.allowConnections(), d.encoding(),
                d.collation(), managed, SYSTEM_DBS.contains(d.name()) || d.name().equals(i.maintenanceDb()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
