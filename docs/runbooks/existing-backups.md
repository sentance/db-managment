# Inspect existing pgBackRest backups

This feature imports backup evidence into pgcp. Existing schedules, repository
contents, retention and PostgreSQL configuration remain under their current owner.
It does not create a backup or perform a restore.

## Connect a source

1. Register the existing PostgreSQL container on **Servers** or **Instances**.
   `MONITOR_ONLY` is sufficient; leave production protection enabled.
2. Open **Backups** and choose **Connect source** for that instance.
3. Enter the existing pgBackRest stanza (for example, `shared`) and a backup-age
   warning threshold. The default is 26 hours, allowing a two-hour margin for a
   daily backup; choose a threshold appropriate to the actual schedule.
4. Choose **Allow catalogue inspection** and confirm with your authenticator.

pgBackRest and `timeout` must exist in the source image, and its PostgreSQL OS user
must already be able to read its repository configuration. pgcp does not copy
credentials or change permissions to make inspection work. Sources without
pgBackRest keep unknown coverage until a supported provider is connected.

The binding pins the full Docker container ID. If Coolify recreates the source,
**Review source** obtains its current ID and requires renewed step-up consent.
Inspection never follows a replacement container automatically. Disconnecting
removes the panel binding and cached evidence only, without deleting any backups.

## Interpret the evidence

| State | Meaning |
|---|---|
| Not configured / unknown | No catalogue is connected; other tools may still provide backups |
| Never | Connected but not yet inspected |
| OK | Metadata was successfully read, not restore-verified |
| Outdated | Last successful inspection is more than 15 minutes old |
| Unreachable | Inspection failed; check agent connectivity, source tools, permissions and repository access |
| Binding stale | The source container changed, disappeared or stopped; review its identity/state |
| No backups | The repository reports no backup sets |
| Stale | Last successful completed backup exceeds the configured age threshold |
| Never verified | No restore verification has been performed by pgcp |

Catalogues are inspected every five minutes while the control plane is running.
The page reloads stored statuses every 30 seconds; **Inspect source** requests a
fresh inspection. Failed inspections retain the previous snapshot and explicitly
mark it as cached; its freshness becomes unknown.

Repository encryption is reported from pgBackRest metadata. `none` means pgBackRest
client-side encryption is absent; it does not describe a provider's server-side
encryption. WAL health compares the latest success and failure, retaining historical
failure counts without treating an old, recovered failure as a current outage.
A quiet database can have no recent archived segment; that observation alone is
not proof of failure. Neither WAL min/max names nor a successful catalogue read
prove an unbroken PITR window. The page does not claim they do.

## Local development

Use [backup-development.md](backup-development.md) to create an isolated source.
The real-Docker inspection test covers parsing a fresh backup, source identity
mismatch, argument rejection, unchanged source configuration/data, and continued
rejection of generic exec on the external fixture.
