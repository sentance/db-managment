package dev.pgcp.cp.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.pgcp.agent.api.AgentApi;
import dev.pgcp.agent.api.DiskUsage;
import dev.pgcp.agent.api.HostMetrics;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.config.CpProperties;
import dev.pgcp.cp.databases.ManagedObjects;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.instances.PgSettings;
import dev.pgcp.cp.jobs.JobService;
import dev.pgcp.cp.servers.ServerService;

@RestController
@RequestMapping("/api")
public class DashboardController {

    public record Warning(String severity, String source, String message) {
    }

    public record Dashboard(List<ServerService.ServerStatus> servers, int instances, Map<String, Integer> instancesByStatus,
            long managedDatabases, List<JobService.Job> recentJobs, List<Warning> warnings) {
    }

    public record Info(String publicHost, String sqlEditorUrl, List<Integer> supportedPgMajors,
            Map<String, String> settingsAllowlist) {
    }

    private final ServerService servers;
    private final InstanceRepository instances;
    private final ManagedObjects objects;
    private final JobService jobs;
    private final AgentClient agent;
    private final CpProperties props;

    public DashboardController(ServerService servers, InstanceRepository instances, ManagedObjects objects, JobService jobs,
            AgentClient agent, CpProperties props) {
        this.servers = servers;
        this.instances = instances;
        this.objects = objects;
        this.jobs = jobs;
        this.agent = agent;
        this.props = props;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        List<ServerService.ServerStatus> statuses = servers.list().stream().map(servers::status).toList();
        List<Warning> warnings = new ArrayList<>();
        for (var s : statuses) {
            if (!s.agentReachable()) {
                warnings.add(new Warning("CRITICAL", s.server().name(), "management agent unreachable: " + s.error()));
            } else {
                warnings.addAll(hostWarnings(s.server().name(), s.metrics()));
            }
        }
        List<Instance> all = instances.listActive();
        Map<String, Integer> byStatus = new TreeMap<>();
        for (Instance i : all) {
            byStatus.merge(i.status(), 1, Integer::sum);
            switch (i.status()) {
                case Instance.FAILED, Instance.UNREACHABLE -> warnings.add(new Warning("CRITICAL", i.name(),
                        "instance is " + i.status() + (i.statusMessage() == null ? "" : ": " + i.statusMessage())));
                case Instance.UNHEALTHY -> warnings.add(new Warning("WARNING", i.name(), "instance is unhealthy"));
                case Instance.STOPPED -> warnings.add(new Warning("INFO", i.name(), "instance is stopped"));
                default -> {
                }
            }
        }
        return new Dashboard(statuses, all.size(), byStatus, objects.countDatabases(), jobs.list(10, null, null), warnings);
    }

    @GetMapping("/settings/info")
    public Info info() {
        return new Info(props.publicHost(), props.sqlEditorUrl(), AgentApi.SUPPORTED_PG_MAJORS.stream().sorted().toList(),
                PgSettings.allowedTypes());
    }

    @GetMapping("/tls/instances-ca")
    public ResponseEntity<String> instancesCa() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pgcp-instances-ca.crt\"")
                .body(agent.instancesCa());
    }

    static List<Warning> hostWarnings(String source, HostMetrics m) {
        List<Warning> out = new ArrayList<>();
        if (m == null) {
            return out;
        }
        for (DiskUsage d : m.disks()) {
            double free = d.totalBytes() == 0 ? 1 : (double) d.freeBytes() / d.totalBytes();
            if (free < 0.10) {
                out.add(new Warning("CRITICAL", source, "disk " + d.label() + " has only " + pct(free) + " free"));
            } else if (free < 0.20) {
                out.add(new Warning("WARNING", source, "disk " + d.label() + " has " + pct(free) + " free"));
            }
        }
        double memFree = m.memTotalBytes() == 0 ? 1 : (double) m.memAvailableBytes() / m.memTotalBytes();
        if (memFree < 0.10) {
            out.add(new Warning("WARNING", source, "only " + pct(memFree) + " of memory available"));
        }
        return out;
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }
}
