# 0008 — Owned logical retention and durable alert delivery

Status: accepted for Phase 2 milestone 3b.

Daily backups already run on the agent during panel outages. Retention must have
the same independence while preserving ownership boundaries and pending restores.

Only successful scheduled exports recorded by the agent's new retention registry
can expire. Existing external repositories, manual exports and pre-registry
artifacts keep their existing lifecycle. Enabling retention is explicit recurring
export policy consent, with conservative defaults of no expiration. Count and age
protections combine: keep the newest configured count, every younger backup, the
newest verified recovery point and all durable restore pins.

The agent atomically persists a pin before CP commits a restore job. Once an
artifact enters `EXPIRING`, new pins fail and no new restore may use it. Pins have
no time-based expiry: downtime must not make a queued restore lose its artifact.
After a terminal restore, a CP coordinator marks successful verification and
releases the pin in one agent state update. A failed metadata transaction can
leave an orphan pin; this sacrifices automatic space reclamation for recoverability.

Deletion uses a bounded, storage-only worker and one exact UUID dump/manifest
pair. It removes the manifest first, never lists a bucket for deletion, never
accepts an arbitrary command or prefix deletion, and shares the global worker
limit with backups/restores. HTTP callers cannot submit the internal expiration
action. Persistent `EXPIRING` and `EXPIRED` outbox events reconcile after outages;
CP ingestion is idempotent and expiration states cannot regress on late replay.
Deleting an S3 key does not purge object versions or change provider lifecycle.

Alerts are persistent conditions with generations. A transaction journals one
notification per active/resolved transition when delivery is enabled. Telegram
credentials remain in the encrypted secret store; settings changes invalidate
queued deliveries for the previous revision. Credential snapshots are read under
the settings lock, then HTTP runs outside database transactions and outside the
shared scheduler thread. Pending generations for one alert send in order.

Delivery uses leases, bounded exponential retries and Telegram rate-limit hints.
It is at least once: a process may stop after Telegram accepted a message but
before the panel stored acknowledgement. Telegram does not provide an application
idempotency key for `sendMessage`; internal deduplication cannot eliminate that
crash window. Operator-visible delivery status keeps this limitation explicit.

Telegram delivery requires a running panel. Agent-owned exports and retention
continue independently. Automatic restore drills and panel disaster recovery
remain milestone 3c.
