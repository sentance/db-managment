package dev.pgcp.cp.backups;

import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.pgcp.cp.jobs.JobService;

@RestController
@RequestMapping("/api/backups")
public class LogicalBackupController {
    private final LogicalBackupService service; private final LogicalRepository repo;
    public LogicalBackupController(LogicalBackupService service,LogicalRepository repo) { this.service=service;this.repo=repo; }
    @GetMapping("/destinations") public List<LogicalBackupService.DestinationView> destinations() { return service.destinations(); }
    @PostMapping("/destinations") public ResponseEntity<LogicalBackupService.DestinationCreated> create(@RequestBody LogicalBackupService.DestinationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.createDestination(request));
    }
    @PostMapping("/destinations/{id}/test") @ResponseStatus(HttpStatus.ACCEPTED)
    public JobService.Submitted test(@PathVariable UUID id) { return service.testDestination(id); }
    @GetMapping("/logical") public List<LogicalRepository.Backup> backups() { return repo.backups(); }
    @PostMapping("/logical/{instanceId}") @ResponseStatus(HttpStatus.ACCEPTED)
    public LogicalBackupService.Submitted backup(@PathVariable UUID instanceId,@RequestBody LogicalBackupService.BackupRequest request) { return service.backup(instanceId,request); }
    @GetMapping("/restores") public List<LogicalRepository.Restore> restores() { return repo.restores(); }
    @PostMapping("/logical/{backupId}/restore") @ResponseStatus(HttpStatus.ACCEPTED)
    public LogicalBackupService.Submitted restore(@PathVariable UUID backupId,@RequestBody LogicalBackupService.RestoreRequest request) { return service.restore(backupId,request); }
}
