package dev.pgcp.cp.databases;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.stereotype.Component;

import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.crypto.SecretStore;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.jobs.JobContext;
import dev.pgcp.cp.pg.InstanceConnector;
import dev.pgcp.cp.pg.PgAdmin;

/**
 * Creates a database with its owner role and an optional read-only role. Every step is idempotent; role rows (with
 * their encrypted passwords) are recorded before this runs, which is what authorizes setting those roles' passwords.
 */
@Component
public class DatabaseProvisioner {

    public record DbSpec(String database, String owner, String readOnlyRole) {
    }

    private final InstanceConnector connector;
    private final ManagedObjects objects;
    private final SecretStore secrets;

    public DatabaseProvisioner(InstanceConnector connector, ManagedObjects objects, SecretStore secrets) {
        this.connector = connector;
        this.objects = objects;
        this.secrets = secrets;
    }

    public void provision(Instance instance, DbSpec spec, JobContext ctx) throws SQLException {
        try (Connection c = connector.open(instance, instance.maintenanceDb(), InstanceConnector.As.ADMIN)) {
            ctx.progress(10, "creating owner role " + spec.owner());
            PgAdmin.ensureLoginRole(c, spec.owner(), passwordOf(instance, spec.owner()));
            ctx.progress(30, "creating database " + spec.database());
            PgAdmin.createDatabase(c, spec.database(), spec.owner());
            if (spec.readOnlyRole() != null) {
                ctx.progress(50, "creating read-only role " + spec.readOnlyRole());
                PgAdmin.ensureLoginRole(c, spec.readOnlyRole(), passwordOf(instance, spec.readOnlyRole()));
            }
        }
        if (serverBelow15(instance)) {
            if (instance.managed()) {
                ctx.progress(60, "securing schema public (PostgreSQL 14)");
                try (Connection su = connector.open(instance, spec.database(), InstanceConnector.As.SUPERUSER)) {
                    PgAdmin.securePublicSchemaPg14(su, spec.owner());
                }
            } else {
                ctx.warn("PostgreSQL 14: schema public still allows CREATE for PUBLIC; fix manually as a superuser");
            }
        }
        if (spec.readOnlyRole() != null) {
            ctx.progress(75, "granting read-only access");
            try (Connection inDb = connector.open(instance, spec.database(), InstanceConnector.As.ADMIN)) {
                PgAdmin.grantAccess(inDb, spec.database(), spec.owner(), spec.readOnlyRole(), PgAdmin.Access.READ_ONLY);
            }
        }
        objects.insertDatabase(instance.id(), spec.database(), spec.owner());
        ctx.progress(95, "database " + spec.database() + " is ready");
    }

    private String passwordOf(Instance instance, String role) {
        var managed = objects.findRole(instance.id(), role)
                .orElseThrow(() -> ApiException.conflict("role " + role + " is not managed by pgcp; refusing to change it"));
        return secrets.read(managed.secretId());
    }

    private boolean serverBelow15(Instance instance) throws SQLException {
        try (Connection c = connector.open(instance, instance.maintenanceDb(), InstanceConnector.As.ADMIN)) {
            return PgAdmin.serverVersionNum(c) < 150000;
        }
    }
}
