package dev.pgcp.agent.api;

import java.util.UUID;

public record CreateInstanceRequest(UUID operationId, InstanceSpec spec) {
}
