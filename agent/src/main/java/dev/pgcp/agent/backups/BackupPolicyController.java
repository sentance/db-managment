package dev.pgcp.agent.backups;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.pgcp.agent.api.*;

@RestController
@RequestMapping("/v1/backup-policies")
public class BackupPolicyController {
    private final BackupPolicyService service;
    public BackupPolicyController(BackupPolicyService service) { this.service=service; }
    @PutMapping public ResponseEntity<BackupPolicyStatus> put(@RequestBody BackupPolicy policy) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.put(policy));
    }
    @GetMapping public List<BackupPolicyStatus> list() { return service.statuses(); }
    @GetMapping("/events") public List<BackupPolicyEvent> events() throws IOException { return service.events(); }
    @DeleteMapping("/events/{id}") public void acknowledge(@PathVariable UUID id) throws IOException { service.acknowledge(id); }
}
