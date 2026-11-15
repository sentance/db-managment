# Backup retention and alerts

Milestone 3b adds opt-in expiration for pgcp scheduled logical backups, a persistent
alert journal and Telegram delivery. Existing external backup repositories and
their schedules/retention are unchanged. Automatic restore drills and panel
disaster recovery remain separate work.

## Retention

Open **Backups → Schedule backups** or **Edit / refresh access** and enable
automatic expiration. Set a minimum backup count (2–3650) and age window
(1–3650 days). Defaults in the form are seven backups and 30 days; expiration
remains off until explicitly selected and saved with step-up confirmation.
Policies created before this feature default to keeping all backups.

Keep every backup covered by any protection:

- The newest configured count of successful recorded backups for this policy.
- Every backup younger than the age window.
- The newest recovery point with a successful verified restore.
- Every backup pinned by a queued or running restore.

Only other recorded scheduled backups may expire. Manual exports and artifacts
created before the retention registry existed are not adopted or deleted. The
registry and pins are credential-free files under
`<PGCP_DATA_DIR>/protection/retention/`, with mode `0600` under `0700` directories.
Preserve this state during upgrades and include it in agent recovery planning.
Losing the registry causes conservative retention of existing artifacts, not a
bucket scan or inferred ownership.

The agent performs retention while the panel is offline. It deletes only the
recorded UUID manifest/dump pair in the policy's destination, manifest first.
The cleanup worker has no source database connection or age decryption identity.
It shares the one-worker limit, so due source exports take priority over new
cleanup. S3 errors retry with backoff from 30 seconds up to one hour. An unknown
worker outcome keeps its original operation ID until reconciled.

Once deletion is committed, the backup becomes `EXPIRING` and refuses new restore
pins even if the offline panel still shows `SUCCEEDED`. A cleanup failure keeps
it `EXPIRING` and reports a retention error; partial deletions cannot be offered
as a successful recovery point. Successful cleanup reports `EXPIRED`. History
remains in the panel. Late completion events cannot resurrect expired objects.

Pausing a policy or disabling retention prevents starting new deletions once the
revision is applied. An already committed deletion finishes, including retries.
Provider versioned buckets may retain earlier object versions after a key is
deleted; pgcp neither purges those versions nor changes bucket lifecycle rules.

## Restore pins

The restore API obtains a durable agent-side pin before committing the queued
job. An expiration race returns 409 before provisioning any target. Manual or
pre-registry backups are untracked and cannot be expired by this feature.

After a terminal restore, the panel coordinator acknowledges verification for a
successful restore and releases the pin. Agent or panel outages keep pins until
that acknowledgement succeeds. Pins never expire solely because time passed.
Failed restore targets remain isolated for inspection as before.

If a metadata transaction fails after pinning but before committing its restore
row, a conservative orphan pin can remain. Inspect the registry's restore IDs and
confirm that no queued/running restore or owned worker needs the backup. Only
then release that orphan through the existing agent mTLS endpoint
`POST /v1/backup-retention/release` with `{backupId,restoreId,verified:false}`.
Do not delete registry files or worker journals to make cleanup advance.

## Alert conditions

**Backups → Backup alerts** shows active conditions and recent resolutions.
Current conditions cover scheduled backup failure, stale or overdue first backup,
unconfirmed agent status, retention failure, latest on-demand export failure and
latest restore failure. Unknown evidence does not resolve an existing backup
condition. Confirmed policy pauses resolve source-backup conditions with a
message that protection was disabled; they are not reported as a fresh backup.
Committed retention failures remain actionable even while a policy is paused.

Repeated observations do not create another alert or notification. A failed
manual export resolves after a later successful export for the same source,
database and destination. A restore alert resolves after a later verified restore
of that backup. These alerts do not claim WAL continuity, general database
availability or low-disk coverage; those broader monitoring integrations are not
part of this checkpoint.

## Telegram

In **Settings → Backup notifications**, supply a bot token and numeric chat ID,
give the bot access to that chat, and enable delivery. Saving requires TOTP.
The token is stored with pgcp's encrypted secret store and is never returned by
the API. Leave its replacement field empty to retain it. See the official
[Telegram Bot API](https://core.telegram.org/bots/api#sendmessage) for bot/chat
permissions and `sendMessage`.

Only new active/resolved transitions observed while enabled enter the delivery
queue. Enabling notifications does not send the full historical journal. Disabling
or changing settings cancels pending deliveries for the previous revision; an HTTP
send already in progress may finish. Saving unchanged settings preserves pending
deliveries. Revoking the token with Telegram is the immediate credential-revocation
path when the panel is unavailable.

The panel must be running to evaluate conditions and send messages. Backups and
enabled retention continue on the agent during panel outages; notifications
resume after the panel returns. This does not alert independently about the
panel's own outage.

Recent delivery status is visible in Settings. Transient failures back off to one
hour with at most eight attempts, including recovered interrupted attempts.
Telegram's `retry_after` is respected up to one day. A stuck sender is reclaimed
after a two-minute lease, and pending generations for one alert cannot overtake
each other. Exhausted deliveries remain `FAILED`; correcting settings affects
future transitions and does not silently replay failed historical messages.

Delivery is at least once. A crash after Telegram accepts a message but before
the database records success may duplicate that message. Journal deduplication
prevents repeated polling from generating copies, but cannot eliminate that
external acknowledgement window. No test in this repository sends to a real
Telegram chat: transport tests use a loopback HTTP stub and application tests use
a mock transport.

## Validation scope

Unit checks cover conservative count/age defaults, latest verified recovery point,
durable pins, restart attachment, retry backoff and unknown worker outcomes. The
PostgreSQL 16/18 Docker matrix deletes one real encrypted S3 pair twice and verifies
an unrelated pair in the same prefix survives. CP integration checks cover
out-of-order expiration replay, restore/deletion races, settings step-up/redaction,
transition deduplication, delivery ordering, rate limits and sender lease recovery.

The assembled smoke test performs a real scheduled export and verified restore,
checks the persisted pin and its release, then clones explicitly aged ciphertext
fixtures into its disposable registry/outbox. With CP stopped, the real agent
expires only eligible pairs and preserves the newest points, original verified
backup and manual export. On restart CP imports expiration results. Age simulation
does not change any host/production clock. Only local Moto storage is used;
remote-provider acceptance and production rollout remain separate.
It also disconnects only its disposable storage service, checks that a real failed
upload opens an alert, reconnects storage without losing its contents, and verifies
that a successful export resolves the condition. Telegram stays disabled throughout.
