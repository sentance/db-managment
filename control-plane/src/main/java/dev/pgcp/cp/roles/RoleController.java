package dev.pgcp.cp.roles;

import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.pg.PgAdmin;

@RestController
@RequestMapping("/api/instances/{instanceId}/roles")
public class RoleController {

    public record LockRequest(boolean locked) {
    }

    public record DropRequest(String confirmName) {
    }

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping
    public List<RoleService.RoleView> list(@PathVariable UUID instanceId) {
        return roles.list(instanceId);
    }

    @GetMapping("/{role}/dependencies")
    public List<PgAdmin.Dependency> dependencies(@PathVariable UUID instanceId, @PathVariable String role) {
        return roles.dependencies(instanceId, role);
    }

    @PostMapping
    public ResponseEntity<RoleService.PasswordResult> create(@PathVariable UUID instanceId,
            @RequestBody RoleService.CreateRequest req) {
        return noStore(roles.create(instanceId, req));
    }

    @PutMapping("/{role}/access")
    public RoleService.RoleView access(@PathVariable UUID instanceId, @PathVariable String role,
            @RequestBody RoleService.AccessRequest req) {
        return roles.setAccess(instanceId, role, req);
    }

    @PostMapping("/{role}/rotate-password")
    public ResponseEntity<RoleService.PasswordResult> rotate(@PathVariable UUID instanceId, @PathVariable String role) {
        return noStore(roles.rotate(instanceId, role));
    }

    @PostMapping("/{role}/reveal-password")
    public ResponseEntity<RoleService.PasswordResult> reveal(@PathVariable UUID instanceId, @PathVariable String role) {
        return noStore(roles.reveal(instanceId, role));
    }

    @PutMapping("/{role}/lock")
    public RoleService.RoleView lock(@PathVariable UUID instanceId, @PathVariable String role, @RequestBody LockRequest req) {
        return roles.setLocked(instanceId, role, req.locked());
    }

    @PostMapping("/{role}/drop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobService.Submitted drop(@PathVariable UUID instanceId, @PathVariable String role, @RequestBody DropRequest req) {
        return new JobService.Submitted(roles.drop(instanceId, role, req.confirmName()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
