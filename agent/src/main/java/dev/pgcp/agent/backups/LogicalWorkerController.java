package dev.pgcp.agent.backups;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.pgcp.agent.api.*;

@RestController
@RequestMapping("/v1/logical-backups")
public class LogicalWorkerController {
    private final LogicalWorkerService workers;
    public LogicalWorkerController(LogicalWorkerService workers) { this.workers = workers; }
    @PostMapping public Operation start(@RequestBody LogicalBackupRequest request) throws Exception {
        if(request.action()==LogicalBackupRequest.Action.EXPIRE) throw dev.pgcp.agent.config.AgentException.forbidden("expiration is owned by the agent retention scheduler");
        return workers.start(request);
    }
    @GetMapping("/{id}") public Operation get(@PathVariable UUID id) { return workers.get(id); }
    @PostMapping("/recovery-key") public ResponseEntity<RecoveryKey> key() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(workers.recoveryKey());
    }
}
