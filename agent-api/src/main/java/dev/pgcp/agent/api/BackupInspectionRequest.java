package dev.pgcp.agent.api;

import java.util.regex.Pattern;

/** An explicitly selected PostgreSQL container and existing stanza, not a command. */
public record BackupInspectionRequest(String containerName, String containerId, String stanza) {
    public static final Pattern CONTAINER_ID = Pattern.compile("^[a-f0-9]{64}$");
    public static final Pattern STANZA = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$");

    public void validate() {
        if (containerName == null || !AgentApi.NETWORK_NAME.matcher(containerName).matches()
                || containerId == null || !CONTAINER_ID.matcher(containerId).matches()
                || stanza == null || !STANZA.matcher(stanza).matches()) {
            throw new IllegalArgumentException("a valid container name, full container ID and stanza are required");
        }
    }
}
