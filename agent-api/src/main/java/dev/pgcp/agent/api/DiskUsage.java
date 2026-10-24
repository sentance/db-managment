package dev.pgcp.agent.api;

public record DiskUsage(String label, String path, long totalBytes, long freeBytes) {
}
