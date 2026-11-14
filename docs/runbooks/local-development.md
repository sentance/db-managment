# Local development on macOS or Linux

Requirements: Docker Desktop (running) or Docker Engine, Docker Compose 2.24.4+
and OpenSSL. Docker builds supply Node 24 and Java 21; no host Java installation is
needed for the container build. Allow several minutes for the first build.

From the repository root:

```bash
cd deploy
./local.sh init
./local.sh up
./local.sh token
```

Open <http://localhost:8480>. Use the setup token to create your **local** admin
and configure an authenticator. The token disappears once setup is complete.
The local database starts empty: production accounts, TOTP secrets, database
registrations and database contents are not copied from the VPS.

`deploy/.env.local` controls the local panel port and settings. HTTP session cookies
work locally because this file sets `PGCP_COOKIE_SECURE=false`. Keep production
cookies secure. The helper explicitly selects the local Compose override and
environment file, so it does not load `deploy/compose.override.yml` or `deploy/.env`.

The local stack uses `pgcp-local-*` containers and volumes. Managed instances use
the application's existing `pgcp-instances` network and `pgcp-*` instance names;
run only one pgcp development stack per Docker engine. Keep test instance names
distinct from other projects. The agent manages your local Docker engine, so only
register disposable local databases for development.

Certificates and keys are generated locally in ignored `deploy/certs/` and
`deploy/secrets/`. Agent state lives in ignored `.local-data/` at the same absolute
path on the host and in the container, as required for managed-instance mounts.
On Docker Desktop, this repository must be in a shared host directory (the default
`/Users` sharing covers a normal checkout on macOS). Metrics describe the agent's
Linux container/VM rather than the Mac itself.

## Frontend development

With the local stack running, use Node 24+ in another terminal:

```bash
cd web
npm ci
PGCP_API=http://localhost:8480 npm run dev
```

Open <http://localhost:5173>. Use `localhost` consistently for both ports so
browser cookies work with the API proxy. After editing backend code, rebuild with
`deploy/local.sh up`. For frontend validation:

```bash
cd web
npm test
npm run build
```

For JVM tests, install JDK 21 and run `./gradlew check` with Docker running.
The Gradle build requests the Java 21 toolchain, even if a newer JDK is installed.

## Stop and resume

```bash
deploy/local.sh logs --tail 100
deploy/local.sh down
deploy/local.sh up
```

`down` preserves database volumes and `.local-data/`. Instances created through
the panel have their own Compose projects; stop or delete those through the panel
before stopping the development stack.

## Source recovery

This checkout was recovered from `/srv/pgcp` on the VPS serving `db.aragorn.fyi`
on 2026-11-14. The deployed source was commit
`c87a625c8eb4983c8becc76ee095a7f5746ed21d`. Its three existing Git commits and
`origin` (`git@github.com:sentance/db-managment.git`) were preserved; the remote
already contained the same commit. The VPS source had no tracked modifications.

Production `.env`, certificates, secret files, Docker volumes, database contents
and the untracked production Compose override were excluded. The production
deployment and shared reverse proxy were not changed. The existing generic VPS
deployment example remains in `deploy/examples/`.
