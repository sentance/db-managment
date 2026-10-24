package dev.pgcp.agent.api;

import java.time.Instant;
import java.util.List;

public record HostMetrics(
        Instant timestamp,
        double cpuPercent,
        double load1,
        long memTotalBytes,
        long memAvailableBytes,
        long swapUsedBytes,
        long diskReadBytesPerSec,
        long diskWriteBytesPerSec,
        List<DiskUsage> disks) {
}
