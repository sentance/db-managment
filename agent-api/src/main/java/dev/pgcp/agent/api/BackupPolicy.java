package dev.pgcp.agent.api;

import java.time.*;
import java.util.UUID;

/** Desired schedule; the template contains credentials and must never appear in a public response. */
public record BackupPolicy(UUID id, long revision, UUID destinationId, String dailyTime, String timezone,
        boolean enabled, boolean runImmediately, int maxAgeHours, LogicalBackupRequest template, Integer keepLast, Integer keepDays) {
    public BackupPolicy { keepLast=keepLast==null?0:keepLast;keepDays=keepDays==null?0:keepDays; }
    public BackupPolicy(UUID id,long revision,UUID destinationId,String dailyTime,String timezone,boolean enabled,
            boolean runImmediately,int maxAgeHours,LogicalBackupRequest template) {
        this(id,revision,destinationId,dailyTime,timezone,enabled,runImmediately,maxAgeHours,template,0,0);
    }
    public void validate(boolean allowHttp) {
        if (id == null || revision < 1 || destinationId == null || maxAgeHours < 24 || maxAgeHours > 8760
                || dailyTime == null || !dailyTime.matches("[0-2][0-9]:[0-5][0-9]")) {
            throw new IllegalArgumentException("policy identity, revision, daily time and maximum age (24–8760 hours) are required");
        }
        LocalTime.parse(dailyTime);
        ZoneId.of(timezone);
        if (!((keepLast==0 && keepDays==0) || (keepLast>=2 && keepLast<=3650 && keepDays>=1 && keepDays<=3650))) {
            throw new IllegalArgumentException("retention requires 2–3650 backups and 1–3650 days, or zero for both to disable");
        }
        if (template == null || template.action() != LogicalBackupRequest.Action.BACKUP
                || template.storage().identity() != null) throw new IllegalArgumentException("an export-only backup template is required");
        template.validate(allowHttp);
    }

    /** One wall-clock occurrence per calendar day, including DST transitions. */
    public Instant nextAfter(Instant after) {
        ZoneId zone = ZoneId.of(timezone);
        LocalDate date = after.atZone(zone).toLocalDate();
        Instant next = date.atTime(LocalTime.parse(dailyTime)).atZone(zone).toInstant();
        return next.isAfter(after) ? next : date.plusDays(1).atTime(LocalTime.parse(dailyTime)).atZone(zone).toInstant();
    }

    public LogicalBackupRequest request(UUID runId) {
        var t = template;
        return new LogicalBackupRequest(runId, t.action(), t.instanceId(), t.containerName(), t.containerId(), t.pgMajor(),
                t.connection(), t.storage(), t.storage().prefix()+"/"+runId+".dump.age",
                t.storage().prefix()+"/"+runId+".manifest.age", null, null);
    }
    @Override public String toString() { return "BackupPolicy[id="+id+", revision="+revision+", template=redacted]"; }
}
