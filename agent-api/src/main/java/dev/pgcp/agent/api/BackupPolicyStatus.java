package dev.pgcp.agent.api;

import java.time.Instant;
import java.util.UUID;

public record BackupPolicyStatus(UUID id, long revision, boolean enabled, Instant nextRunAt, UUID activeRunId,
        Instant lastRunAt, String lastStatus, Instant lastSuccessAt, String health) { }
