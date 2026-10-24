package dev.pgcp.agent.api;

import java.util.UUID;

public record EnsureImageRequest(UUID operationId, int pgMajor) {
}
