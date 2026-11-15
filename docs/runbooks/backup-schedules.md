# Daily logical backup schedules

Milestone 3a adds agent-owned daily exports and in-panel health. Milestone 3b adds
opt-in retention and Telegram alert delivery; see
[retention and alerts](backup-retention-and-alerts.md). Automatic restore drills
and panel disaster recovery are the remaining parts of milestone 3.
Existing external pgBackRest schedules and retention remain with their owner.

## Create and manage a policy

Create and test an S3 destination, then open **Backups → Schedule backups**.
Choose the registered instance, database, tested destination, daily time and IANA
timezone (for example `Europe/Prague`). Confirm recurring export consent with
TOTP. This grants neither database DDL permissions nor source lifecycle access.
The source login must have the export privileges described in
[logical backups](logical-backups.md).

The panel stores the policy and its credential snapshot encrypted with its master
key. The agent receives the revision over mTLS and caches the export-only template
under `<PGCP_DATA_DIR>/protection/policies/`. Directories are mode `0700` and policy
files `0600`. This cache contains the database and S3 credentials required for
offline execution. It does **not** contain the age recovery identity. Protect the
agent data directory as carefully as database credentials; its files are not
encrypted at rest by pgcp.

**PENDING** means the requested revision has not been acknowledged. **APPLIED**
means the latest observation confirms it; **UNCONFIRMED** means the agent is
unreachable or its last observation is more than two minutes old. The health
column reports `UNKNOWN` when evidence is unavailable, `SYNC_PENDING` during an
edit, `AWAITING_FIRST_BACKUP`, `OVERDUE`, `BACKUP_FAILED`, `STALE`, `HEALTHY`, or
`PAUSED`. Freshness uses the successful export's start time, so consuming an old
result after an outage does not make an old snapshot fresh. A successful export
does not imply a verified restore.

Use **Pause / Resume** for future runs. A pause is effective only after the agent
applies it; a panel outage does not revoke a running agent's cached policy. An
active export finishes with its original credential snapshot. Pausing does not
erase cached credentials. Revoke credentials at PostgreSQL/the storage provider
when immediate credential revocation is required, including during an agent
outage. Keep historical storage credentials usable for restores as appropriate.

**Edit / refresh access** replaces the cached source credentials in a new revision.
An active run keeps its prior snapshot until completion. The database, instance
and storage destination are fixed for a policy; create a new policy to change
them. Source container replacement requires explicitly reviewing and consenting
to the newly discovered full container ID. This clears previous-source freshness;
an in-flight result from the old container cannot mark the replacement healthy.
Destination credentials remain immutable as described in the storage runbook.

## Time and missed runs

- Daily times use the selected timezone. During a DST gap, the time moves forward
  by the gap; during an overlap, only the earlier occurrence is scheduled.
- The run UUID is derived from policy UUID and its persisted scheduled instant.
  Agent/panel restarts and policy revisions do not create a second run for that slot.
- On agent downtime, execute at most one catch-up export, then advance to the
  next future daily time. Missed days are not replayed as a backlog.
- Manual exports, scheduled exports and restores share the existing global
  one-worker limit and per-source lifecycle lock. A waiting run retries every
  five seconds and fails after two hours if it cannot start. Worker resource and
  execution limits are unchanged. A failed export waits until the next daily
  slot; a manual backup can be requested sooner.
- The optional first run happens when a new policy is first applied. Editing or
  resuming a policy schedules its next future occurrence without another first run.

## Restart and result delivery

Keep the agent and Docker running for backups to continue while the panel is
stopped. Active snapshots, next-run times and results survive process restarts
on the agent's persistent data mount. This is not protection against losing that
mount or the host. Preserve it during upgrades.

Completed runs first enter the credential-free agent outbox under
`protection/outbox/`. The panel polls every ten seconds, saves the result and its
unique event ID in one metadata transaction, then acknowledges the agent. A lost
acknowledgement safely replays the same event. Results appear in **Saved logical
backups** without a CP job ID; ordinary restore works for successful entries.
An active scheduled run is shown in its policy's execution state until its result
is imported. Run history is retained while the panel is unavailable.

Do not delete outbox entries manually to resolve import problems. Missing panel
instance/destination/policy references keep events pending for reconciliation.
If agent policy files are lost, CP will reapply its desired policies, but prior
run identity/history must be recovered from the original agent data to guarantee
restart deduplication. Losing that data is a disaster recovery case, not a normal
restart. Recovering an old CP snapshot can also require reviewing policy revisions
before resuming scheduling.

Health conditions also feed the persistent alert journal and optional Telegram
delivery. A policy's retention is disabled by default; enabled retention can run
while CP is offline. Continue the existing backup system until provider testing
and rollout are done.

## Development evidence

`BackupPolicyServiceTest` covers agent restarts, durable replay, catch-up, credential
rotation, pauses, lock contention and DST. `ControlPlaneIT` covers step-up,
redaction, transactional replay after a lost acknowledgement and unconfirmed
pause/outage states. The assembled smoke test stops CP before a daily run,
restarts the agent before and after completion, then checks one imported backup
and an acknowledged outbox. It uses disposable PostgreSQL/Moto containers only.

See [backup development](backup-development.md) for commands and validation scope.
