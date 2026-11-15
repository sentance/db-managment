package dev.pgcp.cp.backups;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backups/policies")
public class BackupPolicyController {
    public record Enabled(boolean enabled) { }
    private final BackupPolicyService service;
    public BackupPolicyController(BackupPolicyService service) { this.service=service; }
    @GetMapping public List<BackupPolicyService.PolicyView> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public BackupPolicyService.PolicyView create(@RequestBody BackupPolicyService.PolicyRequest request) { return service.create(request); }
    @PutMapping("/{id}") public BackupPolicyService.PolicyView update(@PathVariable UUID id,@RequestBody BackupPolicyService.PolicyRequest request) { return service.update(id,request); }
    @PutMapping("/{id}/enabled") public BackupPolicyService.PolicyView enabled(@PathVariable UUID id,@RequestBody Enabled request) { return service.enabled(id,request.enabled()); }
}
