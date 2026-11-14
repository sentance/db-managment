package dev.pgcp.agent.api;

import java.time.Instant;
import java.util.List;

/** Allowlisted metadata only; repository credentials, paths and raw errors never leave the agent. */
public record BackupCatalogue(String stanza, int statusCode, List<Repository> repositories,
        List<Archive> archives, List<Backup> backups) {
    public record Repository(int key, int statusCode, String cipher) { }
    public record Archive(String id, String min, String max) { }
    public record Backup(String label, String type, Instant startedAt, Instant finishedAt,
            long databaseBytes, long repositoryBytes, boolean error) { }
}
