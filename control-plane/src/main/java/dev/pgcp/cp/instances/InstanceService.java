package dev.pgcp.cp.instances;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.LifecycleAction;
import dev.pgcp.agent.api.LogLines;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.crypto.Passwords;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.databases.ManagedObjects;
import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;
import dev.pgcp.cp.pg.PgIdent;
import dev.pgcp.cp.servers.ServerService;

@Service
public class InstanceService {

    public enum Exposure {
        /** Reachable only on Docker networks (pgcp-instances and any extra networks). */
        NONE,
        /** Published on 127.0.0.1 of the host (SSH tunnels, host processes). */
        LOCALHOST,
        /** Published on all interfaces; pg_hba then requires TLS for every remote connection. */
        PUBLIC
    }

    public record InitialDatabase(String name, String owner, Boolean createReadOnlyRole) {
    }

    public record CreateManagedRequest(String name, Integer pgMajor, Integer memoryMb, Double cpus,
            Instance.Protection protection, Exposure exposure, Integer hostPort, List<String> extraNetworks,
            Map<String, String> settings, InitialDatabase initialDatabase) {
    }

    public record RegisterExternalRequest(String name, String host, Integer port, String maintenanceDb, String username,
            String password, String sslMode, Instance.ExternalMode mode, Instance.Protection protection,
            String containerName) {
    }

    public record IssuedCredential(String role, String kind, String password) {
    }

    public record CreateResult(InstanceView instance, UUID jobId, List<IssuedCredential> credentials) {
    }

    public record InstanceView(UUID id, UUID serverId, String name, Instance.Kind kind, String status, String statusMessage,
            Integer pgMajor, String pgVersion, String image, String libc, String containerName, String host, int port,
            String sslMode, String bindAddress, Integer hostPort, List<String> extraNetworks,
            Instance.ExternalMode externalMode, Instance.Protection protection, Integer memoryMb, BigDecimal cpus,
            Map<String, String> settings, String adminRole, boolean objectsWritable, java.time.Instant createdAt) {

        static InstanceView of(Instance i) {
            return new InstanceView(i.id(), i.serverId(), i.name(), i.kind(), i.status(), i.statusMessage(), i.pgMajor(),
                    i.pgVersion(), i.image(), i.libc(), i.containerName(), i.host(), i.port(), i.sslMode(), i.bindAddress(),
                    i.hostPort(), i.extraNetworks(), i.externalMode(), i.protection(), i.memoryMb(), i.cpus(), i.settings(),
                    i.adminRole(), i.objectsWritable(), i.createdAt());
        }
    }

    static final String ADMIN_ROLE = "pgcp_admin";
    private static final List<String> SSL_MODES = List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    private final InstanceRepository repo;
    private final ServerService servers;
    private final SecretStore secrets;
    private final JobService jobs;
    private final AgentClient agent;
    private final InstanceConnector connector;
    private final ManagedObjects objects;
    private final StepUp stepUp;
    private final AuditService audit;

    public InstanceService(InstanceRepository repo, ServerService servers, SecretStore secrets, JobService jobs,
            AgentClient agent, InstanceConnector connector, ManagedObjects objects, StepUp stepUp, AuditService audit) {
        this.repo = repo;
        this.servers = servers;
        this.secrets = secrets;
        this.jobs = jobs;
        this.agent = agent;
        this.connector = connector;
        this.objects = objects;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    public List<InstanceView> list() {
        return repo.listActive().stream().map(InstanceView::of).toList();
    }

    public InstanceView get(UUID id) {
        return InstanceView.of(repo.require(id));
    }

    @Transactional
    public CreateResult createManaged(CreateManagedRequest req) {
        String name = req.name();
        try {
            AgentApi.requireValidInstanceName(name);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("instance name must match " + AgentApi.INSTANCE_NAME.pattern());
        }
        if (repo.nameTaken(name)) {
            throw ApiException.conflict("an instance named " + name + " already exists");
        }
        int major = req.pgMajor() == null ? 18 : req.pgMajor();
        if (!AgentApi.SUPPORTED_PG_MAJORS.contains(major)) {
            throw ApiException.badRequest("supported PostgreSQL versions: " + AgentApi.SUPPORTED_PG_MAJORS);
        }
        int memoryMb = req.memoryMb() == null ? 1024 : req.memoryMb();
        if (memoryMb < 256 || memoryMb > 262_144) {
            throw ApiException.badRequest("memoryMb must be between 256 and 262144");
        }
        double cpus = req.cpus() == null ? 1.0 : req.cpus();
        if (cpus < 0.1 || cpus > 128) {
            throw ApiException.badRequest("cpus must be between 0.1 and 128");
        }
        Exposure exposure = req.exposure() == null ? Exposure.NONE : req.exposure();
        String bind = switch (exposure) {
            case NONE -> null;
            case LOCALHOST -> "127.0.0.1";
            case PUBLIC -> "0.0.0.0";
        };
        Integer hostPort = exposure == Exposure.NONE ? null : req.hostPort();
        if (exposure != Exposure.NONE) {
            if (hostPort == null || hostPort < 1024 || hostPort > 65_535) {
                throw ApiException.badRequest("hostPort between 1024 and 65535 is required when the port is published");
            }
            if (repo.hostPortTaken(hostPort)) {
                throw ApiException.conflict("host port " + hostPort + " is already used by another instance");
            }
        }
        List<String> networks = req.extraNetworks() == null ? List.of() : List.copyOf(req.extraNetworks());
        for (String n : networks) {
            if (!AgentApi.NETWORK_NAME.matcher(n).matches()) {
                throw ApiException.badRequest("invalid network name: " + n);
            }
        }
        Map<String, String> settings = PgSettings.withDefaults(memoryMb, req.settings());

        InitialDatabase initial = req.initialDatabase();
        String dbName = null;
        String owner = null;
        String readOnly = null;
        if (initial != null && initial.name() != null && !initial.name().isBlank()) {
            dbName = PgIdent.requireNewName(initial.name(), "database");
            owner = PgIdent.requireNewName(initial.owner() == null || initial.owner().isBlank() ? dbName + "_owner"
                    : initial.owner(), "owner role");
            if (Boolean.TRUE.equals(initial.createReadOnlyRole())) {
                readOnly = PgIdent.requireNewName(dbName + "_ro", "read-only role");
            }
        }

        UUID id = UUID.randomUUID();
        long superuserSecret = secrets.create("pg-superuser", Passwords.generate());
        long adminSecret = secrets.create("pg-role", Passwords.generate());
        String container = AgentApi.containerName(name);
        Instance instance = new Instance(id, servers.local().id(), name, Instance.Kind.MANAGED, Instance.PROVISIONING,
                "queued", major, null, null, "GLIBC", container, container, 5432, "require", bind, hostPort, networks, null,
                req.protection() == null ? Instance.Protection.STANDARD : req.protection(), memoryMb, BigDecimal.valueOf(cpus),
                settings, "postgres", ADMIN_ROLE, adminSecret, superuserSecret, null);
        repo.insert(instance);

        List<IssuedCredential> creds = new ArrayList<>();
        if (dbName != null) {
            creds.add(issueRole(id, owner, ManagedObjects.RoleKind.OWNER));
            if (readOnly != null) {
                creds.add(issueRole(id, readOnly, ManagedObjects.RoleKind.READ_ONLY));
            }
        }
        UUID jobId = jobs.submit(CreateManagedInstanceJob.TYPE, instance.resourceKey(), "Create instance " + name,
                new CreateManagedInstanceJob.Params(id, dbName == null ? null
                        : new CreateManagedInstanceJob.InitialDb(dbName, owner, readOnly)));
        audit.success("instance.create", "instance", id, Map.of("name", name, "pgMajor", major, "memoryMb", memoryMb,
                "exposure", exposure.name(), "initialDatabase", String.valueOf(dbName)));
        return new CreateResult(InstanceView.of(repo.require(id)), jobId, creds);
    }

    private IssuedCredential issueRole(UUID instanceId, String role, ManagedObjects.RoleKind kind) {
        String password = Passwords.generate();
        objects.insertRole(instanceId, role, kind, secrets.create("pg-role", password));
        return new IssuedCredential(role, kind.name(), password);
    }

    /** Registers an existing PostgreSQL without changing anything on it: only read-only queries are executed. */
    @Transactional
    public InstanceView registerExternal(RegisterExternalRequest req) {
        String name = req.name();
        if (name == null || !AgentApi.INSTANCE_NAME.matcher(name).matches()) {
            throw ApiException.badRequest("instance name must match " + AgentApi.INSTANCE_NAME.pattern());
        }
        if (repo.nameTaken(name)) {
            throw ApiException.conflict("an instance named " + name + " already exists");
        }
        if (req.host() == null || !req.host().matches("^[A-Za-z0-9._-]{1,253}$")) {
            throw ApiException.badRequest("invalid host");
        }
        int port = req.port() == null ? 5432 : req.port();
        String sslMode = req.sslMode() == null ? "prefer" : req.sslMode();
        if (!SSL_MODES.contains(sslMode)) {
            throw ApiException.badRequest("sslMode must be one of " + SSL_MODES);
        }
        if (req.username() == null || req.username().isBlank() || req.password() == null) {
            throw ApiException.badRequest("username and password are required");
        }
        String db = req.maintenanceDb() == null || req.maintenanceDb().isBlank() ? "postgres" : req.maintenanceDb();
        Instance.ExternalMode mode = req.mode() == null ? Instance.ExternalMode.MONITOR_ONLY : req.mode();
        if (mode == Instance.ExternalMode.MANAGE_OBJECTS) {
            stepUp.require();
        }
        UUID id = UUID.randomUUID();
        Instance probe = new Instance(id, servers.local().id(), name, Instance.Kind.EXTERNAL, Instance.RUNNING, null, null,
                null, null, null, req.containerName(), req.host(), port, sslMode, null, null, List.of(), mode,
                req.protection() == null ? Instance.Protection.PRODUCTION : req.protection(), null, null, Map.of(), db,
                req.username(), null, null, null);

        String version;
        int versionNum;
        try (Connection c = connector.open(probe, db, req.username(), req.password())) {
            version = PgAdmin.serverVersion(c);
            versionNum = PgAdmin.serverVersionNum(c);
        } catch (SQLException e) {
            audit.failure("instance.register_external", "instance", name, Map.of("host", req.host(), "error", e.getMessage()));
            throw ApiException.badRequest("cannot connect: " + e.getMessage());
        }
        long secret = secrets.create("pg-role", req.password());
        String libc = null;
        if (req.containerName() != null) {
            libc = agent.postgresContainers().stream().filter(c -> c.name().equals(req.containerName())).findFirst()
                    .map(c -> c.libc() == null ? null : c.libc().name()).orElse(null);
        }
        Instance instance = new Instance(id, probe.serverId(), name, Instance.Kind.EXTERNAL, Instance.RUNNING, null,
                versionNum / 10000, version, null, libc, req.containerName(), req.host(), port, sslMode, null, null, List.of(),
                mode, probe.protection(), null, null, Map.of(), db, req.username(), secret, null, null);
        repo.insert(instance);
        audit.success("instance.register_external", "instance", id, Map.of("name", name, "host", req.host(), "port", port,
                "mode", mode.name(), "protection", probe.protection().name()));
        return InstanceView.of(repo.require(id));
    }

    public UUID lifecycle(UUID id, LifecycleAction action) {
        Instance i = repo.require(id);
        if (!i.managed()) {
            throw ApiException.forbidden("external instances are never started or stopped by pgcp");
        }
        if (action != LifecycleAction.START && i.protection() == Instance.Protection.PRODUCTION) {
            stepUp.require();
        }
        UUID jobId = jobs.submit(InstanceLifecycleJob.TYPE, i.resourceKey(), action.name().charAt(0)
                + action.name().substring(1).toLowerCase() + " instance " + i.name(), new InstanceLifecycleJob.Params(id, action));
        audit.success("instance." + action.name().toLowerCase(), "instance", id, Map.of("name", i.name()));
        return jobId;
    }

    /** Deletes a managed instance (job) or unregisters an external one (metadata only). */
    public UUID delete(UUID id, String confirmName, boolean deleteVolume) {
        Instance i = repo.require(id);
        stepUp.require();
        if (!i.name().equals(confirmName)) {
            throw ApiException.badRequest("type the instance name to confirm");
        }
        if (i.protection() == Instance.Protection.PRODUCTION) {
            throw ApiException.forbidden("instance is protected as PRODUCTION; change protection first");
        }
        if (!i.managed()) {
            repo.updateStatus(id, Instance.DELETED, "unregistered; the server itself was not changed");
            audit.success("instance.unregister", "instance", id, Map.of("name", i.name()));
            return null;
        }
        UUID jobId = jobs.submit(DeleteInstanceJob.TYPE, i.resourceKey(), "Delete instance " + i.name(),
                new DeleteInstanceJob.Params(id, deleteVolume));
        audit.success("instance.delete", "instance", id, Map.of("name", i.name(), "deleteVolume", deleteVolume));
        return jobId;
    }

    public InstanceView setProtection(UUID id, Instance.Protection protection) {
        Instance i = repo.require(id);
        if (i.protection() == Instance.Protection.PRODUCTION && protection != Instance.Protection.PRODUCTION) {
            stepUp.require();
        }
        repo.updateProtection(id, protection);
        audit.success("instance.protection", "instance", id, Map.of("from", i.protection().name(), "to", protection.name()));
        return get(id);
    }

    public InstanceView setExternalMode(UUID id, Instance.ExternalMode mode) {
        Instance i = repo.require(id);
        if (i.managed()) {
            throw ApiException.badRequest("only external instances have a management mode");
        }
        stepUp.require();
        repo.updateExternalMode(id, mode);
        audit.success("instance.external_mode", "instance", id, Map.of("from", String.valueOf(i.externalMode()),
                "to", mode.name()));
        return get(id);
    }

    public LogLines logs(UUID id, int tail) {
        Instance i = repo.require(id);
        if (i.containerName() == null) {
            throw ApiException.badRequest("no container is linked to this instance");
        }
        return agent.logs(i.containerName(), tail);
    }
}
