# ADR 0006: backup consent, ownership and recovery boundaries

Status: accepted for Phase 2 implementation.

External projects are the first supported sources. Backup access is an explicit
capability independent of `MONITOR_ONLY` and `MANAGE_OBJECTS`; consenting to backup
inspection grants no object-management, restart or source-configuration rights.
The first connector reads an existing pgBackRest catalogue only. It binds an
instance, a complete immutable Docker container ID and a validated stanza name;
container replacement invalidates the binding until an operator rebinds it.

The agent exposes a typed metadata-inspection operation. Its command and options
are fixed in code, it runs as the PostgreSQL OS user, and it does not accept a
shell command, configuration path or arbitrary arguments. Existing generic exec
restrictions remain in force. Inspection failures return generic, redacted errors;
raw subprocess output and source configuration are never API error messages.

An imported repository has an external owner. pgcp never changes its schedule,
retention, encryption or source archiving configuration. Write tests and cleanup
operate only within prefixes/resources explicitly owned by pgcp. Physical
retention is delegated to pgBackRest, including its backup/WAL dependency handling.

Restores create fresh isolated destinations. No Phase 2 operation overwrites a
source database or reuses its data volume. Physical recovery requires recorded,
tested platform, major-version, extension and collation compatibility. Restored
instances do not archive WAL into their source stanza or join source application
networks. A successful backup and a verified restore are distinct facts.

Storage and encryption credentials use the existing encrypted secret store.
Before shipping logical uploads, a separate ADR must select an established
streaming encryption tool and define offline recovery and unattended verification
key access. No custom cryptographic format or unencrypted production-upload
fallback is allowed. Recovery keys and panel-recovery instructions must be
available independently of the live panel and VPS.

The local fixture is disposable, has no published ports, and uses its own
internal network and Compose volumes. Its trust authentication and unencrypted
local repository are test-only settings; it never connects to production storage.
