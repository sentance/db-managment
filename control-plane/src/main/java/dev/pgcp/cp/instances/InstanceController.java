package dev.pgcp.cp.instances;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.agent.api.LifecycleAction;
import dev.pgcp.agent.api.LogLines;
import dev.pgcp.cp.jobs.JobService;

@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    public record ActionRequest(LifecycleAction action) {
    }

    public record DeleteRequest(String confirmName, boolean deleteVolume) {
    }

    public record ProtectionRequest(Instance.Protection protection) {
    }

    public record ModeRequest(Instance.ExternalMode mode) {
    }

    private final InstanceService instances;

    public InstanceController(InstanceService instances) {
        this.instances = instances;
    }

    @GetMapping
    public List<InstanceService.InstanceView> list() {
        return instances.list();
    }

    @GetMapping("/{id}")
    public InstanceService.InstanceView get(@PathVariable UUID id) {
        return instances.get(id);
    }

    @GetMapping("/settings-allowlist")
    public Map<String, String> settingsAllowlist() {
        return PgSettings.allowedTypes();
    }

    /** The response carries generated passwords for the initial database exactly once. */
    @PostMapping("/managed")
    public ResponseEntity<InstanceService.CreateResult> createManaged(@RequestBody InstanceService.CreateManagedRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(instances.createManaged(req));
    }

    @PostMapping("/external")
    public InstanceService.InstanceView registerExternal(@RequestBody InstanceService.RegisterExternalRequest req) {
        return instances.registerExternal(req);
    }

    @PostMapping("/external/from-container")
    public InstanceService.InstanceView registerFromContainer(@RequestBody InstanceService.RegisterFromContainerRequest req) {
        return instances.registerFromContainer(req);
    }

    @PostMapping("/{id}/actions")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public JobService.Submitted action(@PathVariable UUID id, @RequestBody ActionRequest req) {
        return new JobService.Submitted(instances.lifecycle(id, req.action()));
    }

    @PostMapping("/{id}/delete")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public JobService.Submitted delete(@PathVariable UUID id, @RequestBody DeleteRequest req) {
        return new JobService.Submitted(instances.delete(id, req.confirmName(), req.deleteVolume()));
    }

    @PutMapping("/{id}/protection")
    public InstanceService.InstanceView protection(@PathVariable UUID id, @RequestBody ProtectionRequest req) {
        return instances.setProtection(id, req.protection());
    }

    @PutMapping("/{id}/external-mode")
    public InstanceService.InstanceView mode(@PathVariable UUID id, @RequestBody ModeRequest req) {
        return instances.setExternalMode(id, req.mode());
    }

    @GetMapping("/{id}/logs")
    public LogLines logs(@PathVariable UUID id, @RequestParam(defaultValue = "300") int tail) {
        return instances.logs(id, tail);
    }
}
