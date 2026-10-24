package dev.pgcp.cp.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param serverName      name under which the local VPS is registered
 * @param publicHost      hostname clients use for instances published on a non-loopback address
 * @param sqlEditorUrl    optional link to an external SQL editor (CloudBeaver, pgAdmin)
 * @param stepUpValidity  how long a TOTP re-confirmation unlocks sensitive actions
 * @param devHostAccess   development only: reach managed instances via their published host port instead of the
 *                        Docker network (when the control plane runs outside Docker)
 */
@ConfigurationProperties("pgcp")
public record CpProperties(
        Crypto crypto,
        Path setupTokenFile,
        Agent agent,
        String serverName,
        String publicHost,
        String sqlEditorUrl,
        Duration stepUpValidity,
        boolean devHostAccess) {

    /** @param previousKeyFiles older master keys still accepted for decryption during key rotation */
    public record Crypto(Path masterKeyFile, List<Path> previousKeyFiles) {
    }

    /** @param tls use mutual TLS towards the agent (disable only for local development) */
    public record Agent(String url, boolean tls) {
    }
}
