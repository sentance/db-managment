package dev.pgcp.agent.api;

import java.util.List;
import java.util.Map;

/**
 * A Docker container as seen by the agent. {@code managed} is true only for containers created by pgcp;
 * all other containers are read-only for the agent.
 */
public record ContainerInfo(
        String id,
        String name,
        String image,
        String state,
        String health,
        String status,
        boolean managed,
        String instanceId,
        Integer pgMajor,
        String pgVersion,
        Libc libc,
        List<String> networks,
        Map<String, String> labels,
        Long memoryLimitBytes) {
}
