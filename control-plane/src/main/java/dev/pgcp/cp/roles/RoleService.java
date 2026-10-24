package dev.pgcp.cp.roles;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.crypto.Passwords;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.databases.ConnectionInfoService;
import dev.pgcp.cp.databases.ManagedObjects;
import dev.pgcp.cp.databases.ManagedObjects.ManagedRole;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;
import dev.pgcp.cp.pg.PgIdent;

@Service
public class RoleService {

    public record RoleView(String name, boolean canLogin, boolean superuser, boolean createDb, boolean createRole,
            List<String> memberOf, List<String> databases, boolean managed, String kind, boolean locked,
            boolean passwordStored, Instant rotatedAt, boolean protectedRole) {
    }

    public record DatabaseGrant(String database, PgAdmin.Access access) {
    }

    public record CreateRequest(String name, List<DatabaseGrant> grants) {
    }

    public record AccessRequest(String database, PgAdmin.Access access) {
    }

    public record PasswordResult(String role, String password, List<ConnectionInfoService.ConnectionInfo> connections) {
    }

    private final InstanceRepository instances;
    private final InstanceConnector connector;
    private final ManagedObjects objects;
    private final SecretStore secrets;
    private final JobService jobs;
    private final StepUp stepUp;
    private final AuditService audit;
    private final ConnectionInfoService connections;

    public RoleService(InstanceRepository instances, InstanceConnector connector, ManagedObjects objects, SecretStore secrets,
            JobService jobs, StepUp stepUp, AuditService audit, ConnectionInfoService connections) {
        this.instances = instances;
        this.connector = connector;
        this.objects = objects;
        this.secrets = secrets;
        this.jobs = jobs;
        this.stepUp = stepUp;
        this.audit = audit;
        this.connections = connections;
    }

    public List<RoleView> list(UUID instanceId) {
        Instance i = instances.require(instanceId);
        Map<String, ManagedRole> managed = objects.roles(instanceId).stream()
                .collect(Collectors.toMap(ManagedRole::name, Function.identity()));
        return connector.withMaintenance(i, PgAdmin::roles).stream().map(r -> {
            ManagedRole m = managed.get(r.name());
            return new RoleView(r.name(), r.canLogin(), r.superuser(), r.createDb(), r.createRole(), r.memberOf(),
                    r.databases(), m != null, m == null ? null : m.kind().name(), m != null && m.locked(),
                    m != null && m.secretId() != null, m == null ? null : m.rotatedAt(), isProtected(i, r.name(), r.superuser()));
        }).toList();
    }

    public List<PgAdmin.Dependency> dependencies(UUID instanceId, String role) {
        Instance i = instances.require(instanceId);
        return connector.withMaintenance(i, c -> {
            requireExists(c, role);
            return PgAdmin.dependencies(c, role);
        });
    }

    /** Creates a login role with a generated password (returned once) and optional database grants. */
    public PasswordResult create(UUID instanceId, CreateRequest req) {
        Instance i = requireWritable(instanceId);
        String role = PgIdent.requireNewName(req.name(), "role");
        List<DatabaseGrant> grants = req.grants() == null ? List.of() : req.grants();
        connector.withMaintenance(i, c -> {
            if (PgAdmin.roleExists(c, role)) {
                throw ApiException.conflict("role " + role + " already exists");
            }
            for (DatabaseGrant g : grants) {
                if (!PgAdmin.databaseExists(c, g.database())) {
                    throw ApiException.badRequest("database " + g.database() + " does not exist");
                }
            }
            return null;
        });
        String password = Passwords.generate();
        long secretId = secrets.create("pg-role", password);
        UUID roleId = objects.insertRole(instanceId, role, ManagedObjects.RoleKind.CUSTOM, secretId);
        try {
            connector.withMaintenance(i, c -> {
                PgAdmin.ensureLoginRole(c, role, password);
                return null;
            });
            for (DatabaseGrant g : grants) {
                applyGrant(i, g.database(), role, g.access());
            }
        } catch (RuntimeException e) {
            boolean created = connector.withMaintenance(i, c -> PgAdmin.roleExists(c, role));
            if (!created) {
                objects.deleteRole(roleId);
                secrets.delete(secretId);
            }
            audit.failure("role.create", "role", i.name() + "/" + role, Map.of("error", String.valueOf(e.getMessage())));
            throw e;
        }
        audit.success("role.create", "role", i.name() + "/" + role, Map.of("grants", grants.toString()));
        return new PasswordResult(role, password, connectionsFor(i, role, password));
    }

    public RoleView setAccess(UUID instanceId, String role, AccessRequest req) {
        Instance i = requireWritable(instanceId);
        requireOperable(i, role);
        if (req.access() == null) {
            String owner = ownerOf(i, req.database());
            if (owner.equals(role)) {
                throw ApiException.badRequest("the owner's access cannot be revoked");
            }
            connector.with(i, req.database(), InstanceConnector.As.ADMIN, c -> {
                PgAdmin.revokeAccess(c, req.database(), owner, role);
                return null;
            });
            audit.success("role.revoke", "role", i.name() + "/" + role, Map.of("database", req.database()));
        } else {
            applyGrant(i, req.database(), role, req.access());
            audit.success("role.grant", "role", i.name() + "/" + role, Map.of("database", req.database(),
                    "access", req.access().name()));
        }
        return list(instanceId).stream().filter(r -> r.name().equals(role)).findFirst().orElseThrow();
    }

    /** Sets a new generated password. The new password is returned once and stored encrypted. */
    public PasswordResult rotate(UUID instanceId, String role) {
        Instance i = requireWritable(instanceId);
        requireOperable(i, role);
        String password = Passwords.generate();
        connector.withMaintenance(i, c -> {
            PgAdmin.setPassword(c, role, password);
            return null;
        });
        var managed = objects.findRole(instanceId, role);
        if (managed.isPresent() && managed.get().secretId() != null) {
            secrets.replace(managed.get().secretId(), password);
            objects.markRotated(managed.get().id());
        } else {
            UUID id = objects.insertRole(instanceId, role, ManagedObjects.RoleKind.CUSTOM, secrets.create("pg-role", password));
            objects.markRotated(id);
        }
        audit.success("role.rotate_password", "role", i.name() + "/" + role, Map.of());
        return new PasswordResult(role, password, connectionsFor(i, role, password));
    }

    public RoleView setLocked(UUID instanceId, String role, boolean locked) {
        Instance i = requireWritable(instanceId);
        requireOperable(i, role);
        if (locked && i.protection() == Instance.Protection.PRODUCTION) {
            stepUp.require();
        }
        int terminated = connector.withMaintenance(i, c -> {
            PgAdmin.setLogin(c, role, !locked);
            return locked ? PgAdmin.terminateSessions(c, role) : 0;
        });
        objects.findRole(instanceId, role).ifPresent(m -> objects.setLocked(m.id(), locked));
        audit.success(locked ? "role.lock" : "role.unlock", "role", i.name() + "/" + role,
                Map.of("terminatedSessions", terminated));
        return list(instanceId).stream().filter(r -> r.name().equals(role)).findFirst().orElseThrow();
    }

    public UUID drop(UUID instanceId, String role, String confirmName) {
        Instance i = requireWritable(instanceId);
        stepUp.require();
        if (!role.equals(confirmName)) {
            throw ApiException.badRequest("type the role name to confirm");
        }
        requireOperable(i, role);
        UUID jobId = jobs.submit(DropRoleJob.TYPE, i.resourceKey(), "Drop role " + role + " on " + i.name(),
                new DropRoleJob.Params(instanceId, role));
        audit.success("role.drop", "role", i.name() + "/" + role, Map.of());
        return jobId;
    }

    /** Shows a stored password again. Requires a fresh TOTP confirmation and is audited. */
    public PasswordResult reveal(UUID instanceId, String role) {
        Instance i = instances.require(instanceId);
        stepUp.require();
        ManagedRole m = objects.findRole(instanceId, role).filter(r -> r.secretId() != null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_STORED_PASSWORD",
                        "pgcp does not store a password for this role; rotate it to get a new one"));
        String password = secrets.read(m.secretId());
        audit.success("role.reveal_password", "role", i.name() + "/" + role, Map.of());
        return new PasswordResult(role, password, connectionsFor(i, role, password));
    }

    private void applyGrant(Instance i, String db, String role, PgAdmin.Access access) {
        String owner = ownerOf(i, db);
        connector.with(i, db, InstanceConnector.As.ADMIN, c -> {
            PgAdmin.grantRoleToCurrentUser(c, owner);
            PgAdmin.grantAccess(c, db, owner, role, access);
            return null;
        });
    }

    private String ownerOf(Instance i, String db) {
        return connector.withMaintenance(i, PgAdmin::databases).stream().filter(d -> d.name().equals(db)).findFirst()
                .map(PgAdmin.DatabaseInfo::owner).orElseThrow(() -> ApiException.notFound("database " + db));
    }

    private List<ConnectionInfoService.ConnectionInfo> connectionsFor(Instance i, String role, String password) {
        List<String> dbs = connector.withMaintenance(i, c -> PgAdmin.connectableDatabases(c, role));
        return dbs.stream().map(db -> connections.describe(i, db, role, true, password)).toList();
    }

    /** Roles pgcp must never alter: system roles, superusers and the credentials pgcp itself uses. */
    private void requireOperable(Instance i, String role) {
        boolean superuser = connector.withMaintenance(i, c -> {
            requireExists(c, role);
            return PgAdmin.roles(c).stream().anyMatch(r -> r.name().equals(role) && r.superuser());
        });
        if (isProtected(i, role, superuser)) {
            throw ApiException.forbidden("role " + role + " is protected (system, superuser or used by pgcp)");
        }
    }

    private static boolean isProtected(Instance i, String role, boolean superuser) {
        return superuser || PgIdent.isSystemRole(role) || role.equals(i.adminRole());
    }

    private static void requireExists(Connection c, String role) throws SQLException {
        if (!PgAdmin.roleExists(c, role)) {
            throw ApiException.notFound("role " + role);
        }
    }

    private Instance requireWritable(UUID instanceId) {
        Instance i = instances.require(instanceId);
        if (!i.objectsWritable()) {
            throw ApiException.forbidden("instance " + i.name() + " is registered in MONITOR_ONLY mode");
        }
        if (!Instance.RUNNING.equals(i.status())) {
            throw ApiException.conflict("instance " + i.name() + " is " + i.status());
        }
        return i;
    }
}
