package dev.pgcp.agent.api;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared constants of the control-plane ↔ agent contract.
 * Both sides validate input against these rules; the agent never trusts the caller.
 */
public final class AgentApi {

    public static final String VERSION_PREFIX = "/v1";

    /** Managed instance names become container, volume and compose project names. */
    public static final Pattern INSTANCE_NAME = Pattern.compile("^[a-z][a-z0-9-]{1,30}$");

    /** Docker network names an instance may additionally join. */
    public static final Pattern NETWORK_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}$");

    public static final Set<Integer> SUPPORTED_PG_MAJORS = Set.of(14, 15, 16, 17, 18);

    public static final String LABEL_MANAGED = "pgcp.managed";
    public static final String LABEL_INSTANCE_ID = "pgcp.instance-id";
    public static final String LABEL_PG_MAJOR = "pgcp.pg-major";

    public static final String INTERNAL_NETWORK = "pgcp-instances";

    private AgentApi() {
    }

    public static String containerName(String instanceName) {
        return "pgcp-inst-" + instanceName;
    }

    public static String volumeName(String instanceName) {
        return "pgcp-inst-" + instanceName + "-data";
    }

    /** Image tags are {@code pgcp-postgres:<major>-<build context hash>}, so a changed Dockerfile never reuses an old tag. */
    public static final String IMAGE_REPOSITORY = "pgcp-postgres";

    public static void requireValidInstanceName(String name) {
        if (name == null || !INSTANCE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid instance name");
        }
    }

    public static void requireSupportedMajor(int pgMajor) {
        if (!SUPPORTED_PG_MAJORS.contains(pgMajor)) {
            throw new IllegalArgumentException("unsupported PostgreSQL major version: " + pgMajor);
        }
    }
}
