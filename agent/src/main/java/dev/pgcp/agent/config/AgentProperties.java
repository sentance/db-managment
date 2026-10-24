package dev.pgcp.agent.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param dataDir           state directory; must be bind-mounted at the same path on the host because generated
 *                          compose files reference it and are resolved by the Docker daemon
 * @param imageContextDir   build context of the managed PostgreSQL image
 * @param hostProcPath      host /proc mounted into the agent container (null when running directly on the host)
 * @param hostRootPath      host / mounted read-only, used for os-release
 * @param diskPaths         label → path whose filesystem usage is reported
 * @param minFreeDiskBytes  instance creation is refused below this amount of free space on dataDir
 * @param memHeadroomMb     memory that must remain available after a new instance reserves its limit
 * @param dockerCommand     docker CLI executable
 * @param healthTimeout     how long to wait for a new instance to become healthy
 * @param clientCertCn      required CN of the caller's TLS client certificate (null disables the check, dev only)
 */
@ConfigurationProperties("pgcp.agent")
public record AgentProperties(
        Path dataDir,
        Path imageContextDir,
        Path hostProcPath,
        Path hostRootPath,
        Map<String, Path> diskPaths,
        long minFreeDiskBytes,
        int memHeadroomMb,
        String dockerCommand,
        Duration healthTimeout,
        String clientCertCn) {

    public Path instancesDir() {
        return dataDir.resolve("instances");
    }

    public Path operationsDir() {
        return dataDir.resolve("operations");
    }

    public Path caDir() {
        return dataDir.resolve("ca");
    }
}
