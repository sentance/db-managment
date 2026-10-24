package dev.pgcp.agent.api;

import java.util.UUID;

public record InstanceLifecycleRequest(UUID operationId, UUID instanceId, LifecycleAction action) {
}
