package dev.pgcp.agent.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Durable, credential-free outbox entry; acknowledged only after the panel commits it. */
public record BackupPolicyEvent(UUID id, UUID policyId, long revision, UUID backupId, UUID instanceId,
        UUID destinationId, String database, String containerId, int pgMajor, String status,
        Map<String,String> result, Instant startedAt, Instant finishedAt) { }
