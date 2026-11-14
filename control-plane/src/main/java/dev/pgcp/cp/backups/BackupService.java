package dev.pgcp.cp.backups;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.pgcp.agent.api.BackupCatalogue;
import dev.pgcp.agent.api.BackupInspectionRequest;
import dev.pgcp.agent.api.ContainerInfo;
import dev.pgcp.cp.agentclient.AgentClient;
import dev.pgcp.cp.audit.AuditService;
import dev.pgcp.cp.auth.StepUp;
import dev.pgcp.cp.backups.BackupSourceRepository.Source;
import dev.pgcp.cp.backups.BackupSourceRepository.Snapshot;
import dev.pgcp.cp.common.ApiException;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.instances.InstanceRepository;
import dev.pgcp.cp.monitoring.PgStatsService;

@Service
public class BackupService {
    public record BindRequest(String containerName, String containerId, String stanza, Integer staleAfterHours) { }
    public record View(UUID instanceId, String instanceName, Instance.Kind kind, String provider,
            String containerName, UUID bindingId, String stanza, Integer staleAfterHours, String inspectionState,
            Instant checkedAt, Instant lastSuccessAt, String error, String backupState, String freshness,
            String encryption, String archiveHealth, String verificationState, Snapshot snapshot) { }

    private final InstanceRepository instances;
    private final BackupSourceRepository sources;
    private final AgentClient agent;
    private final PgStatsService stats;
    private final StepUp stepUp;
    private final AuditService audit;
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    public BackupService(InstanceRepository instances, BackupSourceRepository sources, AgentClient agent,
            PgStatsService stats, StepUp stepUp, AuditService audit) {
        this.instances = instances; this.sources = sources; this.agent = agent;
        this.stats = stats; this.stepUp = stepUp; this.audit = audit;
    }

    public List<View> list() {
        Map<UUID, Source> bindings = sources.list().stream().collect(Collectors.toMap(Source::instanceId, Function.identity()));
        return instances.listActive().stream().map(i -> view(i, bindings.get(i.id()), Instant.now())).toList();
    }

    public View bind(UUID instanceId, BindRequest req) {
        stepUp.require();
        Instance instance = instances.require(instanceId);
        var inspection = new BackupInspectionRequest(req.containerName(), req.containerId(), req.stanza());
        try { inspection.validate(); } catch (IllegalArgumentException e) { throw ApiException.badRequest(e.getMessage()); }
        if (req.staleAfterHours() == null || req.staleAfterHours() < 1 || req.staleAfterHours() > 8760) {
            throw ApiException.badRequest("staleAfterHours must be between 1 and 8760");
        }
        if (instance.containerName() == null || !instance.containerName().equals(req.containerName())) {
            throw ApiException.badRequest("select the PostgreSQL container registered for this instance");
        }
        ContainerInfo container = agent.postgresContainers().stream().filter(c -> c.name().equals(req.containerName()))
                .findFirst().orElseThrow(() -> ApiException.notFound("source container"));
        if (!container.id().equals(req.containerId()) || container.pgMajor() == null) {
            throw ApiException.conflict("source container changed; reload discovery before binding");
        }
        sources.bind(instanceId, UUID.randomUUID(), container.name(), container.id(), req.stanza(), req.staleAfterHours());
        audit.success("backup.source.bind", "instance", instanceId, Map.of("stanza", req.stanza(), "provider", "PGBACKREST"));
        return view(instance, sources.find(instanceId).orElseThrow(), Instant.now());
    }

    public View refresh(UUID instanceId) {
        Instance instance = instances.require(instanceId);
        Source source = sources.find(instanceId).orElseThrow(() -> ApiException.notFound("backup source"));
        if (!refreshing.add(instanceId)) { throw ApiException.conflict("backup inspection is already running"); }
        try {
            BackupCatalogue catalogue = agent.inspectBackups(new BackupInspectionRequest(source.containerName(),
                    source.containerId(), source.stanza()));
            if (catalogue == null || !source.stanza().equals(catalogue.stanza())) {
                throw ApiException.unavailable("invalid backup catalogue");
            }
            PgStatsService.Archiver archiver = null;
            try { archiver = stats.archiver(instanceId); } catch (RuntimeException ignored) {
                // A monitoring permission/connectivity failure is separate from repository inspection.
            }
            sources.success(source, new Snapshot(catalogue, archiver));
        } catch (RuntimeException error) {
            boolean changed = error instanceof ApiException a && a.status() == HttpStatus.CONFLICT;
            sources.failure(source, changed ? "BINDING_STALE" : "UNREACHABLE",
                    changed ? "Source container changed or stopped. Reload discovery and review the binding."
                            : "Inspection failed. Check agent connectivity, pgBackRest installation and repository access.");
        } finally { refreshing.remove(instanceId); }
        return view(instance, sources.find(instanceId).orElse(null), Instant.now());
    }

    public void unbind(UUID instanceId) {
        stepUp.require();
        instances.require(instanceId);
        sources.remove(instanceId);
        audit.success("backup.source.unbind", "instance", instanceId, Map.of());
    }

    @Scheduled(fixedDelayString = "${pgcp.backups.poll-interval:5m}", initialDelayString = "${pgcp.backups.initial-delay:30s}")
    public void poll() {
        for (Instance instance : instances.listActive()) {
            if (sources.find(instance.id()).isPresent()) {
                try { refresh(instance.id()); } catch (RuntimeException ignored) {
                    // Refresh persists a generic failure; a concurrent unbind/delete is harmless.
                }
            }
        }
    }

    static View view(Instance instance, Source source, Instant now) {
        if (source == null) {
            return new View(instance.id(), instance.name(), instance.kind(), null, instance.containerName(), null,
                    null, null, "NOT_CONFIGURED", null, null, null, "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", "NEVER_VERIFIED", null);
        }
        Snapshot snapshot = source.snapshot();
        String inspection = source.inspectionState();
        if ("OK".equals(inspection) && (source.lastSuccessAt() == null
                || source.lastSuccessAt().isBefore(now.minus(Duration.ofMinutes(15))))) { inspection = "OUTDATED"; }
        BackupCatalogue c = snapshot == null ? null : snapshot.catalogue();
        BackupCatalogue.Backup latest = c == null ? null : c.backups().stream().filter(b -> !b.error())
                .max(Comparator.comparing(BackupCatalogue.Backup::finishedAt)).orElse(null);
        String backupState = c == null ? "UNKNOWN" : c.statusCode() == 2 && c.backups().isEmpty() ? "NO_BACKUPS" : c.statusCode() != 0
                || c.repositories().stream().anyMatch(r -> r.statusCode() != 0) ? "REPOSITORY_ERROR"
                : c.backups().isEmpty() ? "NO_BACKUPS" : c.backups().stream().anyMatch(BackupCatalogue.Backup::error) ? "BACKUP_ERRORS" : "AVAILABLE";
        String freshness = !"OK".equals(inspection) || "REPOSITORY_ERROR".equals(backupState) || latest == null || latest.finishedAt().isAfter(now.plusSeconds(60))
                ? "UNKNOWN" : latest.finishedAt().isBefore(now.minus(Duration.ofHours(source.staleAfterHours()))) ? "STALE" : "FRESH";
        String encryption = c == null || c.repositories().isEmpty() ? "UNKNOWN"
                : c.repositories().stream().anyMatch(r -> "none".equals(r.cipher())) ? "UNENCRYPTED"
                : c.repositories().stream().allMatch(r -> "aes-256-cbc".equals(r.cipher())) ? "ENCRYPTED" : "UNKNOWN";
        PgStatsService.Archiver a = snapshot == null ? null : snapshot.archiver();
        String archive = !"OK".equals(inspection) || a == null ? "UNKNOWN" : "off".equals(a.archiveMode()) ? "OFF"
                : a.lastFailedAt() != null && (a.lastArchivedAt() == null || a.lastFailedAt().isAfter(a.lastArchivedAt())) ? "ERROR"
                : a.lastArchivedAt() == null || a.lastArchivedAt().isBefore(now.minus(Duration.ofHours(source.staleAfterHours())))
                    ? "NO_RECENT_SUCCESS" : "RECENT_SUCCESS";
        return new View(instance.id(), instance.name(), instance.kind(), "PGBACKREST", source.containerName(), source.bindingId(),
                source.stanza(), source.staleAfterHours(), inspection, source.checkedAt(), source.lastSuccessAt(), source.error(),
                backupState, freshness, encryption, archive, "NEVER_VERIFIED", snapshot);
    }
}
