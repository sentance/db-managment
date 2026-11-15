package dev.pgcp.agent.backups;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/backup-retention")
public class BackupRetentionController {
    public record Pin(UUID backupId,UUID restoreId) { }
    public record Release(UUID backupId,UUID restoreId,boolean verified) { }
    private final BackupRetentionService service;
    public BackupRetentionController(BackupRetentionService service) { this.service=service; }
    @PostMapping("/pins") public Map<String,Boolean> pin(@RequestBody Pin pin) { return Map.of("tracked",service.pin(pin.backupId(),pin.restoreId())); }
    @PostMapping("/release") public void release(@RequestBody Release request) { service.release(request.backupId(),request.restoreId(),request.verified()); }
}
