# Phase 2: backup coverage and proven recovery

Status: implementation in progress. Milestones 0–2 are implemented and locally
verified; milestones 3a–3b add daily agent schedules, durable results, in-panel
health, owned-backup retention and alert delivery. The remaining milestone 3 work
is automatic restore drills and panel disaster recovery. User priority: existing project
databases on the VPS first.

## Completed milestones

- **0 — development baseline:** CI workflow added for frontend checks, JDK 21 JVM
  tests with Docker, and the disposable backup fixture. All 31 existing JVM tests
  passed locally, including agent lifecycle and PostgreSQL-version integration
  tests. The fixture created a physical backup, checked its catalogue and fixture
  data, and removed its own volumes/network. [Hosted CI for the first release passed](https://github.com/sentance/db-managment/actions/runs/43648268343).
  See [backup development](runbooks/backup-development.md).
- **1 — existing backup visibility:** the Backups page, V2 metadata migration,
  typed external pgBackRest inspector, consent/rebinding, periodic catalogue
  refresh, redacted failure states and cached evidence are implemented. The real
  Docker fixture verifies unchanged source configuration/data and continued
  rejection of generic external exec. API integration checks cover authentication,
  step-up, monitoring-only permissions, unavailable sources and replaced bindings.
  All **38 JVM tests and seven frontend tests** passed, the frontend and container
  builds passed, and the updated local stack is healthy with V2 applied. The local
  `/backups` route serves successfully and unauthenticated `/api/backups` returns
  401. Production has not been upgraded or connected to this feature. See
  [existing backups](runbooks/existing-backups.md).
- **2 — encrypted logical backup and restore:** immutable S3-compatible
  destinations, one-time recovery-key export, scoped storage tests, explicit
  export consent, matching-major pg_dump workers, age-encrypted dumps/manifests,
  durable worker reconciliation, fresh isolated restore targets and table-count
  verification are implemented. All **43 JVM tests and nine frontend tests** passed.
  The Docker matrix covers PostgreSQL 16 and 18, pgcrypto ownership, sequence
  restoration, source changes after backup, agent restart reconciliation and
  failed-storage cleanup. The assembled application smoke test also passed through
  real HTTP setup/TOTP, storage creation/test, export, fresh restore, verification
  and deletion. Worker and application builds passed; V3 is applied in the local
  stack. Hosted CI includes the assembled test on subsequent pushes. No production
  source or remote-provider account was used: live-provider acceptance and rollout
  remain separate. See [logical backups](runbooks/logical-backups.md) and
  [ADR 0007](adr/0007-logical-backup-encryption.md).

## Milestone 3 checkpoints

- **3a — daily schedules and durable results:** policy revisions, explicit recurring
  export consent, timezone/DST handling, one catch-up without a backlog, source
  identity checks, protected offline credential snapshots, global/source locks,
  credential rotation, confirmed/pending pause states, durable outbox and
  transactional idempotent imports are implemented. The UI shows fresh, stale,
  failed, overdue and unknown evidence. A successful export's start time is used
  for freshness, including results consumed after an outage. Validation covers
  restart/delivery, consent/redaction, DST, worker preparation and full application
  export/restore through HTTP/mTLS. See [daily schedules](runbooks/backup-schedules.md).
  Local acceptance: **50 JVM tests and ten frontend tests** passed, frontend and
  application/worker builds passed, and the assembled offline-schedule/export/
  restore smoke test passed. V4 is applied to the healthy normal local stack;
  its existing administrator/data were preserved and policy APIs require auth.
  [Hosted CI for milestone 3a passed](https://github.com/sentance/db-managment/actions/runs/43695499628). No remote S3 provider or VPS
  source was used. This checkpoint does not complete milestone 3 or deploy to the VPS.
- **3b — retention and notifications:** implemented and locally verified. Opt-in
  count/age retention records only successful scheduled exports and preserves the
  newest points, newest verified recovery point and durable restore pins. Storage-only
  workers delete exact owned UUID pairs; durable expiration results cannot regress
  on replay. Pauses stop new expiration, while committed deletion finishes. Existing
  external repositories, manual exports and pre-registry artifacts retain their
  existing lifecycle. Persistent failure/stale/agent/retention/restore conditions
  feed ordered Telegram transition deliveries with encrypted credentials, leases,
  bounded retries and resolution updates. Delivery is at least once across an
  external-acknowledgement crash window. Local acceptance: **58 JVM tests and 11
  frontend tests** passed, as did frontend/application/worker builds. The assembled
  test verified tracked restore pins, offline cleanup of explicitly aged disposable
  artifact copies, preserved manual exports and verified points, expiration replay,
  a real failed upload opening an alert and a later export resolving it. Telegram
  HTTP tests use a local stub; CP tests mock its transport and verify rate limits,
  ordering, redaction, lease recovery and the eight-attempt limit. V5 is applied to
  the healthy local stack with existing admin/data preserved. Hosted CI is checked
  separately after pushing; no real Telegram chat, remote provider account or VPS
  database was used. See [retention and alerts](runbooks/backup-retention-and-alerts.md)
  and [ADR 0008](adr/0008-owned-retention-and-alert-delivery.md).
- **3c — automatic drills and panel recovery:** pending. Exercise real isolated
  restores, safely clean up successful drill resources, retain failed destinations,
  and prove recovery of metadata/configuration on a fresh stack using off-host
  artifacts and separately held keys.

## Product outcome

For each project database, the panel should answer: **Is it protected? What can I
restore? When did we last prove that restore works?** An operator can create a
backup and restore it into an isolated destination without overwriting a live
database. Automated backups continue when the control plane is unavailable.

A completed upload and a successful restore verification are separate states.
Logical snapshots and physical/PITR protection are separate capabilities.

## Starting point

The repository has authentication, TOTP, external-instance registration, managed
instances, databases/roles, jobs, audit, encrypted secret storage and monitoring.
At the initial baseline, Backups and Restore used `PlannedView.vue`; S3 and Telegram
settings were placeholders. Milestone 1 now provides `BackupsView.vue`, inspection
endpoints and source metadata. Milestone 2 adds logical restore, S3 destinations,
backup creation and durable workers. Milestone 3a adds daily schedules and result
delivery; milestone 3b adds retention and notifications. Automatic drills,
panel recovery and physical/PITR restore remain unimplemented.
The managed image already installs pgBackRest.

Read-only VPS checks for this plan found:

| Source | Observed state | Planning consequence |
|---|---|---|
| Shared project PostgreSQL | PG 16.14, existing `postgres-pgbackrest:16` image; pgBackRest 2.57.0; stanza `shared`; latest listed differential backup completed 2026-11-14 01:03 Europe/Prague; repository status `ok` | Import visibility first; preserve the existing backup system |
| Shared repository | `cipher: none`; WAL archiving enabled with recent successes and historical failures | Report encryption and archive health accurately; repository status alone does not prove recovery or continuous WAL coverage |
| `task_db` instance | PG 18.4, `postgres:18-alpine`, `archive_mode=off` | Prioritise opt-in logical backup coverage; separately discover any pgbackweb coverage |
| Other infrastructure | Coolify DB, pgbackweb DB and pgcp metadata DB also exist | Inventory and track coverage; pgcp itself needs a recovery path |
| VPS capacity | About 4 GiB available memory and 133 GiB disk free at inspection; swap in use | Bound worker concurrency and restore resources; measure again before a drill |

The existing inventory identifies the shared image as Alpine/musl and its remote
repository as R2. Revalidate exact image, extensions, storage configuration and
backup coverage during onboarding; the inspection above did not test a restore
or enumerate pgbackweb schedules. No production settings were changed.

## Delivery sequence

| Milestone | Deliverable | Acceptance gate |
|---|---|---|
| 0. Development baseline | Linux CI for existing JVM tests and Docker integration checks, frontend tests/build; a repeatable local backup fixture and smoke-test instructions compatible with the local stack | Baseline results recorded; a disposable source can be created and cleaned up without touching other Docker projects |
| 1. Existing backup visibility | Real Backups page: sources, provider, last backup, sizes, archive health, encryption, freshness and verification state. Read existing pgBackRest JSON metadata. Unknown coverage is shown explicitly | A local fixture matching the shared instance appears correctly; stale, unreachable and never-verified states are distinct; no source reconfiguration |
| 2. Logical backup and restore | S3-compatible destinations, opt-in per-database `pg_dump -Fc`, encrypted remote artifacts/manifests, history/progress, restore to a fresh isolated instance and a new database | Back up a fixture, change its data, restore the saved version, verify rows/schema/sequence values; failures never publish partial artifacts as successful |
| 3. Operational protection | Agent-owned schedules, retention, durable result delivery, failure/stale alerts, automatic restore drills, and pgcp metadata/configuration recovery | Backups run while CP is stopped; restart causes no duplicate scheduled run; failed uploads and failed restores alert; a fresh panel can be recovered using off-host artifacts and separately held keys |
| 4. Existing physical restore and PITR | Read existing repositories and restore a selected backup or timestamp into a new compatible instance; retain source backup ownership outside pgcp | Demonstrate a successful restore from the supported existing image family, then a timed recovery fixture; source data and source repository remain unchanged |
| 5. Managed physical backup lifecycle | Configure pgBackRest for pgcp-managed instances: full/diff/incr, WAL archiving, encryption, retention and schedules | Fresh managed instance passes backup, restart, restore and PITR drills; enabling archiving on an existing instance has an explicit restart plan |

Milestones 0–2 form the first useful release for existing projects. Milestone 3
makes that release operationally dependable. Physical/PITR work is a later release
within Phase 2, rather than a prerequisite for shipping backup visibility.

### Milestone 1 scope

Add a typed external backup-inspection capability. The agent currently restricts
generic fixed-command execution to managed containers; keep that restriction.
An approved external-source binding may run only the fixed pgBackRest metadata
inspection operation for the bound PostgreSQL container and stanza. Validate
container identity and reject stale bindings after container replacement. The API
must accept neither a caller-supplied shell command nor an arbitrary filesystem path.

Update ADR 0002 to describe this narrow informational capability. Keep backup
consent separate from external `MANAGE_OBJECTS`: reading backup status or allowing
a logical export must not grant DDL, lifecycle or configuration permissions.
No `check`, `expire`, `stanza-create`, backup invocation or scheduler takeover is
part of importing an existing repository. Verify the inspection connector with
local containers before deploying it against the shared production source.

### Milestone 2 scope

Implement one S3-compatible destination contract covering endpoint, region,
bucket, prefix, TLS verification and credential references; exercise it against
a local S3-compatible fixture and then the selected remote provider. Separate
imported read-only repositories from destinations pgcp owns. A destination write
test may touch only a unique test object under its configured owned prefix.

Run dumps in short-lived agent-owned workers using a compatible PostgreSQL client
major. A monitoring login may lack export permissions: preflight database access,
table/sequence permissions, RLS and extension requirements, and document manual
least-privilege onboarding. Do not silently escalate credentials or alter the source.

Restore first into a fresh managed instance and a new database, with an explicit
owner mapping. Include required extensions and role/grant mappings in the manifest;
a single-database dump is not a complete cluster disaster-recovery archive.
Use an established streaming encryption implementation selected in an ADR before
shipping uploads. Document key recovery outside the VPS; use dedicated test keys
and storage during development. Reuse existing encrypted `SecretStore` metadata.

### Milestone 3 scope

Persist agent policy revisions, time zones, next-run times and stable run IDs.
Choose an explicit missed-run policy (proposed: one catch-up run, no backlog storm).
Use the same per-source locking for manual and scheduled operations. Bound global
worker concurrency (initially one), CPU, memory, temporary disk use and execution
time; provide backoff and clean up incomplete uploads. Maintain a durable result
outbox with idempotent CP ingestion and replay after outages.

Ship failure/stale-backup alerts and an in-panel health state first; add Telegram
delivery with retry, deduplication and recovery notifications. Persist the scoped
credentials needed for offline schedules in protected agent files, with explicit
rotation/revocation behaviour. Missing credentials must surface as a failed policy,
not an apparently healthy schedule.

Restore verification must boot the restored database and execute checks, recording
what was checked, when, and how long it took. It must not rely solely on archive
listing or checksum validation. Scheduled drill cleanup may remove only its own
labelled resources; retain diagnostics on failure. Verification destinations must
not join production application networks or reach production database services.

Back up pgcp metadata and the configuration needed to reconstruct its state.
Inventory exactly which agent CA, policies and secret references must be recovered.
Keep recovery instructions and required decryption keys available without the
running panel; test recovery on a fresh local stack.

### Milestones 4–5 scope

Record physical source compatibility: PostgreSQL major, platform/architecture,
image identity, libc/collation dependencies, extensions and tablespaces. Restore
the existing Alpine source with a tested compatible image; do not treat the default
Debian managed image as interchangeable. Reject unknown/incompatible combinations.

Restored instances use fresh volumes and identities and have WAL archiving disabled
to prevent writes into the source stanza. Repository access is read-only during
recovery. Accept timezone-qualified recovery timestamps, check that an appropriate
base backup exists, and report missing WAL honestly. Do not infer a guaranteed
recovery window from the minimum and maximum archived segment names alone.

Existing schedules and retention stay with their current owner. Any later takeover
or encrypted replacement repository requires a separate migration/runbook; do not
change encryption or expire backups in the existing repository implicitly. For
repositories pgcp owns, let pgBackRest manage physical backup/WAL dependency chains.

## Technical foundations to address before long-running jobs

Milestone 2 addresses these foundations for logical workers: `LogicalWorkerService`
stores durable worker identities and reconciles results; CP reconnects by operation
ID; worker pipes keep dump data separate from diagnostics; credentials use temporary
protected files; workers receive storage egress; and `OperationService` shares target
reservations with them. Generic Phase 1 operations retain their existing behaviour.
Milestone 3a extends these guarantees to policy scheduling and result outboxes;
the remaining operational protection checkpoints are listed above.

Original baseline findings:

- `OperationService` currently marks running operations failed on agent restart;
  it does not reconstruct their work. Add persisted operation descriptors and
  labelled-worker reconciliation. Reattach surviving workers; restart non-resumable
  failed dumps as a new attempt after cleanup. A timeout must not leave an
  untracked worker running or release its source lock prematurely.
- `AgentClient.await` currently has a fixed 30-minute limit. Separate temporary CP
  unavailability and UI polling limits from the actual operation outcome; allow
  policy-specific deadlines and reconcile results after reconnection.
- `CommandRunner` logs argv and merges bounded stdout/stderr. Backups need streaming
  output separate from diagnostics and credentials outside argv. Avoid putting
  secrets in job parameters, manifests, logs, error bodies or Docker command arguments.
- The agent's internal network has no outbound connectivity. Give backup workers
  the approved database network and storage egress they need; keep Docker access
  in the agent and do not remove existing control-plane isolation wholesale.
- Source lifecycle changes and scheduled backups must share agent-side exclusion,
  even while CP is unavailable. Retention and cleanup must verify resource ownership.

## Suggested implementation structure

| Area | Change |
|---|---|
| `agent-api/` | Typed inspection, export, restore, policy and backup-run contracts |
| `agent/.../backups/` | Provider adapters, worker lifecycle, policy scheduler and result outbox |
| `control-plane/.../backups/` | Backup catalogue, policy APIs, job integration and audit |
| Flyway migrations | Add destination, source binding, artifact/run, policy and verification records in V2+; do not edit V1 |
| `web/` | Replace backup/restore placeholders with working workflows; remove each `soon` marker only when its feature works |
| `images/`, `deploy/` | Versioned worker images, compatible restore fixtures and scoped mounts/networking |
| `docs/` | Provider permissions, key recovery, failure runbooks and measured restore results |

Keep provider operations behind a small interface, with logical exports and
pgBackRest inspection as separate implementations. Store tool versions, source
identity, artifact checksum, encryption/key reference and verification outcome.
Do not build a generic plugin framework for this phase.

## First three pull requests

1. **Baseline and recovery contract:** record existing test results, establish CI,
   add local backup fixtures, and document external backup consent, restore isolation,
   storage ownership and encryption/key recovery decisions.
2. **Existing backup inventory:** typed pgBackRest metadata connector, parser/error
   coverage, minimal catalogue migration/API and a usable Backups page. Prove that
   inspecting a source leaves configuration and existing schedules untouched.
3. **One database backup and verified restore:** complete the on-demand logical
   path with test storage, encrypted artifacts, a fresh restore destination and
   end-to-end checks. Then opt in an existing project after local validation.

Deploy each working slice separately. Track a database's protection level explicitly:
unknown, configured, successful backup, verified restore, and measured recovery
target. Proposed initial logical-backup RPO is 24 hours; agree per-project targets
before enabling schedules. Measure RTO during drills rather than promising it.

## Deferred scope

Database branches/TTL remain Phase 3; DBLab/ZFS remains Phase 4. Multi-server agents,
automatic failover, in-place production restores and automatic migration of existing
Coolify databases are outside this phase. Existing external PostgreSQL configuration,
archives, production networks and reverse-proxy settings stay under their current
operator until an explicit migration is designed.

## References

- Current code: `JobWorker`, `OperationService`, `AgentClient`, `CommandRunner`,
  `DockerGateway`, `V1__init.sql`, `PlannedView.vue` and `SettingsView.vue`.
- Existing design: [Architecture](architecture.md), [external-instance ADR](adr/0005-external-instances-are-read-only-by-default.md),
  [VPS inventory](vps-inventory-tgok.md), [disaster recovery](runbooks/disaster-recovery.md).
- [pgBackRest command reference](https://pgbackrest.org/command.html): JSON metadata
  inspection and the need for an external scheduler.
- [pgBackRest configuration reference](https://pgbackrest.org/configuration.html):
  repository encryption and disabling archiving on restored clusters.
- [PostgreSQL pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html):
  per-database scope, global objects and client/server version compatibility.
- [PostgreSQL continuous archiving](https://www.postgresql.org/docs/current/continuous-archiving.html):
  physical recovery depends on a base backup and a continuous WAL sequence.
