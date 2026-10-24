package dev.pgcp.agent.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.ContainerCredentials;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.agent.api.CreateInstanceRequest;
import dev.pgcp.agent.api.DeleteInstanceRequest;
import dev.pgcp.agent.api.EnsureImageRequest;
import dev.pgcp.agent.api.HostFacts;
import dev.pgcp.agent.api.HostMetrics;
import dev.pgcp.agent.api.InstanceLifecycleRequest;
import dev.pgcp.agent.api.LogLines;
import dev.pgcp.agent.api.Operation;
import dev.pgcp.agent.api.OperationType;
import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.docker.DockerGateway;
import dev.pgcp.agent.host.HostService;
import dev.pgcp.agent.instances.ImageService;
import dev.pgcp.agent.instances.InstanceManager;
import dev.pgcp.agent.ops.OperationService;
import dev.pgcp.agent.tls.InstanceCa;

/** The complete agent API. There is deliberately no endpoint that accepts a command or a file path. */
@RestController
@RequestMapping(AgentApi.VERSION_PREFIX)
public class AgentController {

    private final HostService host;
    private final DockerGateway docker;
    private final InstanceManager instances;
    private final ImageService images;
    private final OperationService operations;
    private final InstanceCa ca;

    public AgentController(HostService host, DockerGateway docker, InstanceManager instances, ImageService images,
            OperationService operations, InstanceCa ca) {
        this.host = host;
        this.docker = docker;
        this.instances = instances;
        this.images = images;
        this.operations = operations;
        this.ca = ca;
    }

    @GetMapping("/host/facts")
    public HostFacts facts() {
        return host.facts();
    }

    @GetMapping("/host/metrics")
    public HostMetrics metrics() {
        return host.metrics();
    }

    @GetMapping("/containers")
    public List<ContainerInfo> containers(@RequestParam(defaultValue = "true") boolean postgresOnly) {
        return docker.listContainers(postgresOnly);
    }

    /** Logs of any PostgreSQL container (read-only, allowed for external instances too). */
    @GetMapping("/containers/{name}/logs")
    public LogLines logs(@PathVariable String name, @RequestParam(defaultValue = "200") int tail) {
        if (!AgentApi.NETWORK_NAME.matcher(name).matches()) {
            throw AgentException.badRequest("invalid container name");
        }
        ContainerInfo info = docker.inspect(name).orElseThrow(() -> AgentException.notFound("container not found"));
        if (info.pgMajor() == null) {
            throw AgentException.forbidden("logs are only available for PostgreSQL containers");
        }
        return new LogLines(info.name(), docker.logs(info.id(), Math.max(1, Math.min(tail, 5000))));
    }

    /** Credentials of an existing PostgreSQL container, for registering it as an external instance. */
    @PostMapping("/containers/{name}/postgres-credentials")
    public ContainerCredentials postgresCredentials(@PathVariable String name) {
        if (!AgentApi.NETWORK_NAME.matcher(name).matches()) {
            throw AgentException.badRequest("invalid container name");
        }
        return docker.postgresCredentials(name);
    }

    @PostMapping("/images/ensure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Operation ensureImage(@RequestBody EnsureImageRequest req) {
        return operations.submit(req.operationId(), OperationType.ENSURE_IMAGE, "image:" + req.pgMajor(),
                progress -> images.ensure(req.pgMajor(), progress));
    }

    @PostMapping("/instances")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Operation createInstance(@RequestBody CreateInstanceRequest req) {
        if (req.spec() == null) {
            throw AgentException.badRequest("spec is required");
        }
        String name = req.spec().name();
        return operations.submit(req.operationId(), OperationType.CREATE_INSTANCE, "instance:" + name,
                progress -> instances.create(req.spec(), progress));
    }

    @GetMapping("/instances/{name}")
    public ContainerInfo instance(@PathVariable String name) {
        return instances.describe(name);
    }

    @PostMapping("/instances/{name}/lifecycle")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Operation lifecycle(@PathVariable String name, @RequestBody InstanceLifecycleRequest req) {
        if (req.action() == null) {
            throw AgentException.badRequest("action is required");
        }
        return operations.submit(req.operationId(), OperationType.INSTANCE_LIFECYCLE, "instance:" + name,
                progress -> instances.lifecycle(name, req.instanceId(), req.action(), progress));
    }

    @PostMapping("/instances/{name}/delete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Operation delete(@PathVariable String name, @RequestBody DeleteInstanceRequest req) {
        return operations.submit(req.operationId(), OperationType.DELETE_INSTANCE, "instance:" + name,
                progress -> instances.delete(name, req.instanceId(), req.deleteVolume(), progress));
    }

    @GetMapping("/operations/{id}")
    public Operation operation(@PathVariable UUID id) {
        return operations.get(id).orElseThrow(() -> AgentException.notFound("operation not found"));
    }

    @GetMapping(value = "/tls/instances-ca", produces = MediaType.TEXT_PLAIN_VALUE)
    public String instancesCa() {
        return ca.caCertificatePem();
    }
}
