package dev.pgcp.agent.api;

import java.util.List;

public record HostFacts(
        String hostname,
        String os,
        String kernel,
        int cpuCores,
        long memTotalBytes,
        long swapTotalBytes,
        String dockerVersion,
        List<DiskUsage> disks,
        List<String> filesystems) {
}
