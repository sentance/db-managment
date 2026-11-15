# ADR 0007: age-encrypted logical backups and isolated restore workers

Status: accepted for Phase 2 milestone 2.

Use PostgreSQL's matching-major `pg_dump`/`pg_restore`, the standard `age` command
line tool with an X25519 recipient, and the AWS CLI's S3-compatible client. pgcp
does not implement encryption or S3 request signing. Both the dump and its recovery
manifest are encrypted before upload. The manifest is uploaded last; an incomplete
upload is never recorded as a successful backup.

Generate a dedicated recovery key when creating a destination. Return its identity
once with `Cache-Control: no-store` so the operator can save it outside the VPS.
Store the identity and S3 credentials in the existing encrypted secret store.
The public recipient is not secret. This first release keeps the identity online
to support unattended verification; offline-only identities are a future option.
Changing storage/key configuration requires creating a new destination so old
artifacts keep their original credential/key reference.

Workers receive credentials through a protected read-only file, never Docker
arguments or Docker environment variables. Only the agent has Docker access.
Backup runs stream pg_dump output through age, staging ciphertext only. Restore
runs decrypt into pg_restore's input, without writing a plaintext dump. Worker
diagnostics and failures are fixed messages rather than raw tool output.

Export consent is distinct from monitoring and pgBackRest catalogue inspection.
The operator selects a source container's immutable ID and a database, confirming
use of the registered login. A restricted login may fail export preflight; pgcp
does not grant new source privileges. A repeatable-read exported snapshot ties
verification counts and dump contents to the same source state.

Restore uses a fresh managed instance in a dedicated internal Docker network,
without production application networks or published ports. The agent requires a
restore-operation label matching the submitted operation, and rejects ordinary
managed instances as restore targets. Source owners/ACLs are mapped to one explicit
new owner; the original cluster's roles, passwords and grants are not recreated.
Extension versions and row counts are recorded and checked. Trusted extensions
are recreated by pg_restore as the mapped owner; unsupported privileged/non-default
installations fail preflight. Unsupported extension
requirements fail rather than silently producing a partial restore.

Durable worker identities and result files allow reconciliation after agent/control
plane restarts. Manual and scheduled lifecycle changes share the agent's target
locks. Worker concurrency, memory, runtime and staging file size are bounded.
Failed restore destinations remain isolated for inspection and can be deleted
through the normal managed-instance workflow.

References: [age](https://github.com/FiloSottile/age),
[AWS CLI S3](https://docs.aws.amazon.com/cli/latest/reference/s3/cp.html),
[PostgreSQL pg_restore](https://www.postgresql.org/docs/current/app-pgrestore.html).
