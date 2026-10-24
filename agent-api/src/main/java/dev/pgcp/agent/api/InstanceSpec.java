package dev.pgcp.agent.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Desired state of a managed PostgreSQL instance.
 *
 * @param settings          PostgreSQL parameters, already validated against the control-plane allowlist
 * @param bindAddress       host address to publish the port on, or null to keep the instance on Docker networks only
 * @param hostPort          host port to publish, required when bindAddress is set
 * @param superuserPassword used only for initdb; the agent scrubs it from disk after the first healthy start
 * @param tlsHostnames      extra subject alternative names for the server certificate
 */
public record InstanceSpec(
        UUID instanceId,
        String name,
        int pgMajor,
        int memoryMb,
        double cpus,
        int shmMb,
        Map<String, String> settings,
        String bindAddress,
        Integer hostPort,
        List<String> extraNetworks,
        String superuserPassword,
        List<String> tlsHostnames) {

    @Override
    public String toString() {
        return "InstanceSpec[instanceId=" + instanceId + ", name=" + name + ", pgMajor=" + pgMajor
                + ", memoryMb=" + memoryMb + ", cpus=" + cpus + ", bindAddress=" + bindAddress
                + ", hostPort=" + hostPort + ", extraNetworks=" + extraNetworks + "]";
    }
}
