package dev.pgcp.cp.servers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;

@RestController
@RequestMapping("/api/servers")
public class ServerController {

    /** A PostgreSQL container found on the host, and whether it is already registered in pgcp. */
    public record DiscoveredContainer(ContainerInfo container, boolean registered) {
    }

    private final ServerService servers;
    private final AgentClient agent;
    private final InstanceRepository instances;

    public ServerController(ServerService servers, AgentClient agent, InstanceRepository instances) {
        this.servers = servers;
        this.agent = agent;
        this.instances = instances;
    }

    @GetMapping
    public List<ServerService.ServerStatus> list() {
        return servers.list().stream().map(servers::status).toList();
    }

    @GetMapping("/{id}")
    public ServerService.ServerStatus get(@PathVariable UUID id) {
        return servers.status(servers.find(id).orElseThrow(() -> ApiException.notFound("server")));
    }

    /** Read-only discovery of PostgreSQL containers; nothing is changed on the host. */
    @GetMapping("/{id}/containers")
    public List<DiscoveredContainer> containers(@PathVariable UUID id) {
        servers.find(id).orElseThrow(() -> ApiException.notFound("server"));
        Set<String> known = instances.listActive().stream().map(Instance::containerName).filter(n -> n != null)
                .collect(Collectors.toSet());
        return agent.postgresContainers().stream().map(c -> new DiscoveredContainer(c, known.contains(c.name()))).toList();
    }
}
