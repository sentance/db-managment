package dev.pgcp.agent.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A long-running agent operation. Submitting the same operation id twice returns the existing operation. */
public record Operation(
        UUID id,
        OperationType type,
        String target,
        OperationStatus status,
        int progress,
        String message,
        String error,
        Instant createdAt,
        Instant finishedAt,
        Map<String, String> result) {
}
