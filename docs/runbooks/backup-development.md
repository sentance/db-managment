# Backup development and validation

Use JDK 21, Node 24, Docker with Compose, and Python 3. CI runs the following
checks on Linux without production credentials:

```bash
./gradlew check -PdockerTests --no-daemon
cd web && npm ci && npm test && npm run build
```

The logical-backup matrix includes PostgreSQL 16/18, pgcrypto, local S3, encrypted
artifacts, restart reconciliation, isolated restore and sequence/row checks. A
separate assembled test uses fresh application containers and a fresh administrator
to exercise the real HTTP and mTLS paths end to end. It also saves a daily policy,
stops the panel before its due time, restarts the agent, verifies an offline export,
then restarts both services and checks one imported result and outbox acknowledgement:

```bash
python3 deploy/logical-smoke-test.py
```

It builds its own images, creates only disposable `pgcp-smoke-*` resources and a
uniquely labelled restore instance, and removes them after completion. Its files
are under ignored `.local-data/smoke-tests/`. It does not reset the normal local
administrator or use production credentials. CI runs this test as well. See
[logical backup operations and limits](logical-backups.md).

JVM reports are in each module's `build/reports/tests/test/`. On macOS, docker-java
does not read the Docker CLI context. If `/var/run/docker.sock` is absent, set
`DOCKER_HOST` to the socket shown by `docker context inspect`, for example:

```bash
export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## Disposable backup source

From the repository root:

```bash
deploy/backup-fixture.sh up
deploy/backup-fixture.sh backup
deploy/backup-fixture.sh info
deploy/backup-fixture.sh container
deploy/backup-fixture.sh down
```

This builds a PG 16/pgBackRest source, initialises three fixture orders and a local
repository, and enables WAL archiving. `down` deletes only this fixture's Compose
volumes and containers. No ports are published; no external storage or existing
database is used. The fixture database uses trust authentication on its isolated
network only. Keep these settings out of production deployments.

`PGCP_FIXTURE_PROJECT=pgcp-fixture-mytest` selects a distinct fixture project.
For an automatic create/backup/catalogue-check/cleanup cycle, run:

```bash
deploy/backup-fixture.sh smoke
```

The full application smoke test remains `deploy/smoke-test.sh --cleanup` for the
Linux deployment. It performs initial admin setup and restarts the selected stack,
so use it only with a disposable installation. On the normal Mac development
stack, use the fixture and JVM integration suite instead; they do not change your
local administrator or restart your panel. A local Compose-aware application
smoke wrapper is tracked separately from this backup fixture.
