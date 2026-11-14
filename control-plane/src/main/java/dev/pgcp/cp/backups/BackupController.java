package dev.pgcp.cp.backups;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService backups;
    public BackupController(BackupService backups) { this.backups = backups; }

    @GetMapping
    public List<BackupService.View> list() { return backups.list(); }

    @PutMapping("/sources/{instanceId}")
    public BackupService.View bind(@PathVariable UUID instanceId, @RequestBody BackupService.BindRequest req) {
        return backups.bind(instanceId, req);
    }

    @PostMapping("/sources/{instanceId}/refresh")
    public BackupService.View refresh(@PathVariable UUID instanceId) { return backups.refresh(instanceId); }

    @PostMapping("/sources/{instanceId}/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable UUID instanceId) { backups.unbind(instanceId); }
}
