package dev.pgcp.agent.api;

/** C library of a PostgreSQL image. Physical backups may only be restored into the same family (collations differ). */
public enum Libc {
    GLIBC,
    MUSL,
    UNKNOWN
}
