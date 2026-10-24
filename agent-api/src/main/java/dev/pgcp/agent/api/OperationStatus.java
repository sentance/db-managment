package dev.pgcp.agent.api;

public enum OperationStatus {
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
