package dev.pgcp.agent.api;

public record RecoveryKey(String recipient, String identity) {
    @Override public String toString() { return "RecoveryKey[recipient=" + recipient + ", identity=redacted]"; }
}
