package dev.pgcp.cp.instances;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Metadata of a PostgreSQL instance (cluster). Secret ids point into the encrypted secret table. */
public record Instance(
        UUID id,
        UUID serverId,
        String name,
        Kind kind,
        String status,
        String statusMessage,
        Integer pgMajor,
        String pgVersion,
        String image,
        String libc,
        String containerName,
        String host,
        int port,
        String sslMode,
        String bindAddress,
        Integer hostPort,
        List<String> extraNetworks,
        ExternalMode externalMode,
        Protection protection,
        Integer memoryMb,
        BigDecimal cpus,
        Map<String, String> settings,
        String maintenanceDb,
        String adminRole,
        Long adminSecretId,
        Long superuserSecretId,
        Instant createdAt) {

    public enum Kind {
        MANAGED,
        EXTERNAL
    }

    /** External instances are read-only unless explicitly switched to MANAGE_OBJECTS. */
    public enum ExternalMode {
        MONITOR_ONLY,
        MANAGE_OBJECTS
    }

    public enum Protection {
        PRODUCTION,
        STANDARD,
        EPHEMERAL
    }

    public static final String PROVISIONING = "PROVISIONING";
    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";
    public static final String UNHEALTHY = "UNHEALTHY";
    public static final String UNREACHABLE = "UNREACHABLE";
    public static final String FAILED = "FAILED";
    public static final String DELETING = "DELETING";
    public static final String DELETED = "DELETED";

    public String resourceKey() {
        return "instance:" + id;
    }

    public boolean managed() {
        return kind == Kind.MANAGED;
    }

    /** Whether pgcp may create or change databases and roles on this instance. */
    public boolean objectsWritable() {
        return managed() || externalMode == ExternalMode.MANAGE_OBJECTS;
    }
}
