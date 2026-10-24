package dev.pgcp.cp.monitoring;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/instances/{instanceId}/monitoring")
public class MonitoringController {

    private final PgStatsService stats;

    public MonitoringController(PgStatsService stats) {
        this.stats = stats;
    }

    @GetMapping
    public PgStatsService.Overview overview(@PathVariable UUID instanceId) {
        return stats.overview(instanceId);
    }

    @GetMapping("/activity")
    public List<PgStatsService.Session> activity(@PathVariable UUID instanceId) {
        return stats.activity(instanceId);
    }

    @GetMapping("/locks")
    public List<PgStatsService.BlockedSession> locks(@PathVariable UUID instanceId) {
        return stats.locks(instanceId);
    }
}
