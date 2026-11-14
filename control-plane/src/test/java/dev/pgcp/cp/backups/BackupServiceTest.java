package dev.pgcp.cp.backups;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.pgcp.agent.api.BackupCatalogue;
import dev.pgcp.cp.backups.BackupSourceRepository.Snapshot;
import dev.pgcp.cp.backups.BackupSourceRepository.Source;
import dev.pgcp.cp.instances.Instance;
import dev.pgcp.cp.monitoring.PgStatsService;

class BackupServiceTest {
    private final Instant now = Instant.parse("2026-11-14T12:00:00Z");
    private final Instance instance = mock(Instance.class);

    @Test
    void distinguishesUnknownCoverageEmptyRepositoryFreshAndStaleBackups() {
        assertThat(BackupService.view(instance, null, now).backupState()).isEqualTo("UNKNOWN");
        var c = catalogue(List.of(backup(now.minusSeconds(3600), false)), 0, "aes-256-cbc");
        var fresh = BackupService.view(instance, source("OK", now, c, null), now);
        assertThat(fresh.freshness()).isEqualTo("FRESH");
        assertThat(fresh.encryption()).isEqualTo("ENCRYPTED");
        assertThat(fresh.verificationState()).isEqualTo("NEVER_VERIFIED");
        assertThat(BackupService.view(instance, source("OK", now, catalogue(List.of(backup(now.minusSeconds(100_000), false)), 0, "none"), null), now).freshness()).isEqualTo("STALE");
        assertThat(BackupService.view(instance, source("OK", now, catalogue(List.of(), 0, "none"), null), now).backupState()).isEqualTo("NO_BACKUPS");
        assertThat(BackupService.view(instance, source("OK", now, catalogue(List.of(), 2, "none"), null), now).backupState()).isEqualTo("NO_BACKUPS");
    }

    @Test
    void neverCallsOldFailedOrFutureEvidenceFresh() {
        var c = catalogue(List.of(backup(now.minusSeconds(10), false)), 0, "none");
        assertThat(BackupService.view(instance, source("UNREACHABLE", now, c, null), now).freshness()).isEqualTo("UNKNOWN");
        var outdated = BackupService.view(instance, source("OK", now.minusSeconds(3600), c, null), now);
        assertThat(outdated.inspectionState()).isEqualTo("OUTDATED");
        assertThat(outdated.freshness()).isEqualTo("UNKNOWN");
        assertThat(BackupService.view(instance, source("OK", now, catalogue(List.of(backup(now.plusSeconds(120), false)), 0, "none"), null), now).freshness()).isEqualTo("UNKNOWN");
        var failed = BackupService.view(instance, source("OK", now, catalogue(List.of(backup(now, true)), 0, "none"), null), now);
        assertThat(failed.backupState()).isEqualTo("BACKUP_ERRORS");
        assertThat(failed.freshness()).isEqualTo("UNKNOWN");
    }

    @Test
    void differentiatesHistoricalArchiveFailuresFromTheMostRecentOutcome() {
        var c = catalogue(List.of(), 0, "none");
        var recovered = new PgStatsService.Archiver(4, now.minusSeconds(20), 3, now.minusSeconds(100), "on");
        assertThat(BackupService.view(instance, source("OK", now, c, recovered), now).archiveHealth()).isEqualTo("RECENT_SUCCESS");
        var failing = new PgStatsService.Archiver(4, now.minusSeconds(100), 3, now.minusSeconds(20), "on");
        assertThat(BackupService.view(instance, source("OK", now, c, failing), now).archiveHealth()).isEqualTo("ERROR");
        assertThat(BackupService.view(instance, source("OK", now, c, new PgStatsService.Archiver(0, null, 0, null, "off")), now).archiveHealth()).isEqualTo("OFF");
    }

    private Source source(String state, Instant checked, BackupCatalogue c, PgStatsService.Archiver archiver) {
        return new Source(UUID.randomUUID(), UUID.randomUUID(), "postgres", "a".repeat(64), "shared", 26,
                state, checked, checked, null, new Snapshot(c, archiver));
    }
    private BackupCatalogue catalogue(List<BackupCatalogue.Backup> backups, int status, String cipher) {
        return new BackupCatalogue("shared", status, List.of(new BackupCatalogue.Repository(1, 0, cipher)), List.of(), backups);
    }
    private BackupCatalogue.Backup backup(Instant time, boolean error) {
        return new BackupCatalogue.Backup("20261108-000021F", "full", time.minusSeconds(5), time, 10, 5, error);
    }
}
