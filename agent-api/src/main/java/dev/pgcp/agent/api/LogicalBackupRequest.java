package dev.pgcp.agent.api;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** Fixed worker operation. Secrets are transported over mTLS and never rendered by toString. */
public record LogicalBackupRequest(UUID operationId, Action action, UUID instanceId, String containerName,
        String containerId, int pgMajor, Connection connection, Storage storage, String artifactKey,
        String manifestKey, String sha256, String targetOwner) {
    public enum Action { BACKUP, RESTORE, TEST_STORAGE, EXPIRE }

    public record Connection(String host, int port, String database, String username, String password,
            String sslMode, String caCertificate) {
        @Override public String toString() { return "Connection[redacted]"; }
    }
    public record Storage(String endpoint, String region, String bucket, String prefix, String accessKey,
            String secretKey, String recipient, String identity) {
        @Override public String toString() { return "Storage[redacted]"; }
        public void validate(boolean allowHttp) {
            if (endpoint != null && !endpoint.isBlank()) {
                URI uri = URI.create(endpoint);
                if ((!"https".equals(uri.getScheme()) && !(allowHttp && "http".equals(uri.getScheme())))
                        || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null || (uri.getPath() != null && !Set.of("", "/").contains(uri.getPath()))) {
                    throw new IllegalArgumentException("storage endpoint must use HTTPS and contain no credentials or path");
                }
            }
            if (region == null || !region.matches("[a-zA-Z0-9-]{1,64}") || bucket == null
                    || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || prefix == null
                    || !prefix.matches("pgcp/[a-zA-Z0-9/_-]{1,180}") || prefix.endsWith("/")
                    || accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()
                    || recipient == null || !recipient.matches("age1[0-9a-z]{58}")) {
                throw new IllegalArgumentException("valid storage credentials, owned pgcp/ prefix and age recipient are required");
            }
        }
    }
    public void validate(boolean allowHttp) {
        if (operationId == null || action == null || instanceId == null || storage == null) {
            throw new IllegalArgumentException("operation, action, instance and storage are required");
        }
        AgentApi.requireSupportedMajor(pgMajor);
        storage.validate(allowHttp);
        if (action == Action.TEST_STORAGE) return;
        if (action == Action.EXPIRE) {
            String base=storage.prefix()+"/";
            String name=artifactKey==null?"":artifactKey.substring(Math.min(base.length(),artifactKey.length()));
            if (!name.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.dump\\.age")
                    || !artifactKey.equals(base+name) || !java.util.Objects.equals(manifestKey,base+name.replace(".dump.age",".manifest.age"))
                    || storage.identity()!=null || connection!=null) throw new IllegalArgumentException("expiration requires one owned UUID artifact pair and no database/recovery credentials");
            return;
        }
        new BackupInspectionRequest(containerName, containerId, "logical").validate();
        if (connection == null || !containerName.equals(connection.host()) || connection.port() != 5432
                || connection.database() == null || connection.database().isEmpty() || connection.database().length() > 63
                || connection.database().contains("\0") || connection.username() == null || connection.username().isBlank()
                || connection.password() == null || !Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full").contains(connection.sslMode())) {
            throw new IllegalArgumentException("a database connection to the bound container on port 5432 is required");
        }
        String owned = storage.prefix() + "/";
        if (artifactKey == null || manifestKey == null || !artifactKey.startsWith(owned) || !manifestKey.startsWith(owned)
                || artifactKey.contains("..") || manifestKey.contains("..")) {
            throw new IllegalArgumentException("artifact keys must belong to the configured prefix");
        }
        if (action == Action.BACKUP && (!artifactKey.equals(owned + operationId + ".dump.age")
                || !manifestKey.equals(owned + operationId + ".manifest.age"))) {
            throw new IllegalArgumentException("backup keys must be unique to this operation");
        }
        if (action == Action.RESTORE && (sha256 == null || !sha256.matches("[a-f0-9]{64}") || targetOwner == null
                || !targetOwner.matches("[a-z][a-z0-9_]{0,62}") || storage.identity() == null || storage.identity().isBlank())) {
            throw new IllegalArgumentException("restore requires an integrity hash, recovery identity and new owner");
        }
    }
    @Override public String toString() { return "LogicalBackupRequest[operationId=" + operationId + ", action=" + action + "]"; }
}
