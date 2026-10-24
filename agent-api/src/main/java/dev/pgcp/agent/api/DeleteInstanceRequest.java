package dev.pgcp.agent.api;

import java.util.UUID;

/** Removes a managed instance. The data volume is kept unless {@code deleteVolume} is explicitly true. */
public record DeleteInstanceRequest(UUID operationId, UUID instanceId, boolean deleteVolume) {
}
