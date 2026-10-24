package dev.pgcp.agent.api;

/**
 * Credentials a PostgreSQL container was started with (POSTGRES_USER / POSTGRES_PASSWORD / POSTGRES_DB).
 * Returned only to the control plane, which stores them encrypted and never shows them.
 */
public record ContainerCredentials(String container, String user, String password, String database) {

    @Override
    public String toString() {
        return "ContainerCredentials[container=" + container + ", user=" + user + ", database=" + database + "]";
    }
}
