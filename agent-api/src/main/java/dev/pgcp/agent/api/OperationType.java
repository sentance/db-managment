package dev.pgcp.agent.api;

public enum OperationType {
    ENSURE_IMAGE,
    CREATE_INSTANCE,
    INSTANCE_LIFECYCLE,
    DELETE_INSTANCE,
    LOGICAL_BACKUP,
    LOGICAL_RESTORE,
    TEST_STORAGE,
    EXPIRE_BACKUP
}
