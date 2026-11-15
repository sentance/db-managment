# Encrypted logical backup and restore

This feature provides on-demand and opt-in daily database backups. Existing external PostgreSQL
configuration, pgBackRest repositories and their schedules remain unchanged.
See [daily schedules](backup-schedules.md) for agent-owned execution during panel
outages. Remote objects are not yet expired automatically.

## Storage and recovery key

1. Create a bucket with your S3-compatible provider. Give pgcp credentials for
   object upload, download, deletion and multipart-upload aborts in its assigned
   prefix. Bucket creation is outside this feature.
2. Open **Backups → Add storage**. Supply the HTTPS endpoint, region, existing
   bucket and credentials. Leave endpoint empty for AWS S3; use the actual AWS
   region. Cloudflare R2 uses region `auto` and an account-specific endpoint.
   See [Cloudflare's S3 API documentation](https://developers.cloudflare.com/r2/api/s3/api/).
3. Save the displayed `age` recovery identity outside the VPS. It is shown once.
   The public recipient and owned prefix remain visible. Credentials and the
   identity are stored encrypted with pgcp's existing master key.
4. Choose **Test read / write**. The worker creates, reads back and deletes a
   unique test object under that destination's new `pgcp/<destination-id>/` prefix.
   Backups require a successful test.

Destination settings are immutable in this release. Create a new destination to
change credentials, endpoint or encryption key; existing artifacts retain their
original destination/key reference. There is no destination deletion endpoint.
Keep the existing credentials usable until those artifacts are migrated.

## Back up an existing project

Choose **Back up a database**, select a registered Docker source, its database and
a tested destination, then confirm export with your authenticator. This consent
is independent of pgBackRest inspection and `MANAGE_OBJECTS`. The full source
container ID is checked by the agent before starting a worker.

The registered login must already be able to export the complete database,
including tables, sequences, large objects and extension configuration data.
Monitoring permission alone may be insufficient. pgcp does not grant privileges,
change ownership, bypass RLS or enable WAL archiving on external sources. A
permission or compatibility problem fails the job rather than silently omitting data.
The first release connects to the selected Docker container's internal port 5432.

The worker uses a matching PostgreSQL-major client, holds a repeatable-read
snapshot, inventories table row counts and extensions, and gives the same exported
snapshot to `pg_dump -Fc`. Counts scan all tables and add source read load. Dumps
flow through `age`; only ciphertext is staged on disk. The encrypted dump uploads
first, followed by its encrypted manifest. Only then is the backup marked successful.

The panel records the ciphertext checksum, size, source major and recovery
manifest. A successful backup remains **Never verified** until a restore passes.
A single-database dump does not replace cluster/global-role or pgcp metadata backups.

## Restore and verify

On **Restore**, choose a successful logical backup and provide a fresh instance
name, database name and owner. The job creates a 512 MB managed PostgreSQL instance
with the backup's major version and a dedicated internal network. It publishes no
ports and joins no source/application networks. The control plane joins that
network to initialise and manage the destination.

The agent requires the destination's immutable ID, managed-instance ID and
restore-operation label to match. An ordinary existing instance is rejected as a
restore target. Before loading data, the worker checks manifest/ciphertext integrity,
target emptiness and extension-version availability. It decrypts directly into
`pg_restore --no-owner --no-acl`, mapping ownership to the selected new owner.
Original roles, passwords and grants are not recreated. Extensions must already
be installed at the required version or be trusted extensions whose source
version matches the target image's default. `pg_restore` creates the latter as
the mapped owner in the source schema. Privileged or non-default extension
installations fail preflight in this release.

After restore, table inventory and row counts must match the saved snapshot.
Successful verification records its time and counts; failed targets remain isolated
for inspection. Use the normal instance delete workflow to remove an unwanted
target and, when selected, its volume. This also removes its owned recovery
network after disconnecting the control plane. Connecting applications or promoting
a recovered instance is a separate operator action, not an automatic cutover.

## Worker limits and restarts

- One logical worker at a time, with one CPU, 512 MB memory and 128 processes.
- At most 4 GiB per staged ciphertext file; at least 5 GiB free staging disk is
  required before launch. This initial limit must be assessed for larger projects.
- A one-hour worker alarm and an agent deadline bound execution. Table inventory
  queries have a 60-second statement timeout; the inventory is limited to 2,000 tables.
- Credential files are mounted read-only and removed after completion/failure.
  They are not Docker arguments/environment variables, job parameters or result files.
- Workers have durable names, owner labels, descriptors and results. The agent
  reconciles them after restart, and CP reconnects by operation ID. Failed
  non-resumable exports require a new backup request, rather than an implicit duplicate.
- Lifecycle operations share the same per-instance agent lock. Timeouts stop the
  owned worker before releasing that lock. Ciphertext from a hard host failure
  can remain orphaned remotely; remote retention/garbage collection is a later milestone.

The worker image is built on first use for each PostgreSQL major. Its tag includes
the worker context hash, and tool versions are recorded in each manifest. First
use needs image/package registry access by the Docker daemon.

## Offline recovery

Keep the destination endpoint, bucket/region, S3 credentials and exported recovery
identity independently of the panel. Objects are paired by backup UUID:
`<uuid>.dump.age` and `<uuid>.manifest.age` under the destination prefix.
Download both, decrypt the manifest using standard `age`, check the encrypted
dump's SHA-256 against it, and restore only into a fresh compatible PostgreSQL
destination. For example, with files already downloaded and a new empty database:

```bash
age -d -i recovery-key.txt backup.manifest.age > recovery-manifest.json
age -d -i recovery-key.txt backup.dump.age | pg_restore --exit-on-error --no-owner --no-acl --dbname=new_recovery_database
```

Supply the appropriate new owner and connection parameters locally; recreate the
required extensions before restore. Preserve pgcp's separate master key and
metadata backups to recover its saved configuration and credentials.

## Development and local S3 fixture

`LogicalBackupDockerIT` runs a disposable PostgreSQL/S3 fixture for majors 16 and
18, verifies a changed source restores to its earlier snapshot, checks sequence
state, exercises agent restart reconciliation and failed-storage credential
cleanup, and rejects restoring onto the external source.

```bash
./gradlew :agent:test -PdockerTests --tests '*LogicalBackupDockerIT'
```

The S3 fixture uses Moto, fake credentials and no published ports. HTTP storage
is rejected by default. Tests explicitly allow it. For a separate local test
environment only, both CP and agent can set `PGCP_BACKUPS_ALLOW_HTTP_STORAGE=true`.
An agent-only `pgcp.backups.storage-network` setting can select a dedicated local
storage-fixture Docker network; the default is the ordinary Docker bridge for
storage egress. Neither setting is required for normal HTTPS remote storage.
The agent has no S3 egress itself; the isolated worker has the required connections.
