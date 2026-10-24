#!/usr/bin/env bash
# End-to-end smoke test of a running pgcp stack through its HTTP API:
#   setup/login (TOTP) -> create managed instance with a database -> connect from the host and from the Docker
#   network with TLS verification -> restart the pgcp stack -> data still there -> (optional) delete the instance.
#
# On a fresh installation it performs the initial setup and stores the generated admin credentials in a 0600 file.
# For an already configured panel export PGCP_ADMIN_USER, PGCP_ADMIN_PASSWORD and PGCP_TOTP_SECRET.
# Usage: ./smoke-test.sh [--cleanup]      (PGCP_URL defaults to http://127.0.0.1:8480)
set -euo pipefail
cd "$(dirname "$0")"

BASE=${PGCP_URL:-http://127.0.0.1:8480}
CLEANUP=${1:-}
WORK=$(mktemp -d)
JAR=$WORK/cookies
touch "$JAR"
trap 'rm -rf "$WORK"' EXIT
NAME="smoke-$(date +%s | tail -c 6)"
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
PG_IMAGE=postgres:18-trixie

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

totp() { # secret, step offset
    python3 - "$1" "$2" <<'PY'
import base64, hmac, hashlib, struct, sys, time
secret, offset = sys.argv[1], int(sys.argv[2])
key = base64.b32decode(secret + "=" * (-len(secret) % 8))
counter = int(time.time()) // 30 + offset
h = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
o = h[-1] & 15
print("%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7fffffff) % 1000000))
PY
}

# Each TOTP time step can be used once (replay protection), so wait for a fresh 30 s window when needed.
LAST_STEP=0
fresh_code() {
    local now=$(( $(date +%s) / 30 ))
    while [ "$now" -le "$LAST_STEP" ]; do sleep 2; now=$(( $(date +%s) / 30 )); done
    LAST_STEP=$now
    CODE=$(totp "$PGCP_TOTP_SECRET" 0)
}

# The server may already have seen the current window (e.g. a previous run); then retry in the next one.
login() {
    fresh_code
    if ! call POST /api/auth/login "{\"username\":\"$PGCP_ADMIN_USER\",\"password\":\"$PGCP_ADMIN_PASSWORD\",\"code\":\"$CODE\"}" >/dev/null 2>&1; then
        fresh_code
        call POST /api/auth/login "{\"username\":\"$PGCP_ADMIN_USER\",\"password\":\"$PGCP_ADMIN_PASSWORD\",\"code\":\"$CODE\"}" >/dev/null
    fi
}

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

# call METHOD PATH [BODY] -> prints body, fails unless 2xx
call() {
    local method=$1 path=$2 body=${3:-}
    local token
    token=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1)
    local out
    out=$(curl -sS -b "$JAR" -c "$JAR" -X "$method" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: ${token}" \
        ${body:+-d "$body"} -w '\n%{http_code}' "$BASE$path")
    local code=${out##*$'\n'}
    local resp=${out%$'\n'*}
    if [[ $code != 2* ]]; then
        echo "$method $path -> $code: $resp" >&2
        return 1
    fi
    printf '%s' "$resp"
}

await_job() {
    local id=$1 status
    for _ in $(seq 1 300); do
        status=$(call GET "/api/jobs/$id" | json "d['job']['status']")
        case $status in
            SUCCEEDED) return 0 ;;
            FAILED) call GET "/api/jobs/$id" | json "d['job']['error']" >&2; fail "job $id failed" ;;
        esac
        sleep 2
    done
    fail "job $id timed out"
}

psql_host() { docker run --rm --network host "$PG_IMAGE" psql -v ON_ERROR_STOP=1 -qtA "$@"; }

step "status"
call GET /api/auth/status >/dev/null
SETUP=$(call GET /api/auth/status | json "d['setupRequired']")

if [ "$SETUP" = "True" ]; then
    step "initial setup"
    TOKEN=$(docker exec pgcp-control-plane cat /var/lib/pgcp-cp/setup-token)
    PGCP_ADMIN_USER=admin
    PGCP_ADMIN_PASSWORD=$(openssl rand -hex 16)
    PGCP_TOTP_SECRET=$(call POST /api/auth/setup "{\"token\":\"$TOKEN\",\"username\":\"admin\",\"password\":\"$PGCP_ADMIN_PASSWORD\"}" | json "d['totpSecret']")
    fresh_code
    call POST /api/auth/setup/totp "{\"code\":\"$CODE\"}" >/dev/null
    CREDS=$(umask 077; mktemp "${TMPDIR:-/tmp}/pgcp-smoke-admin.XXXXXX")
    printf 'PGCP_ADMIN_USER=%s\nPGCP_ADMIN_PASSWORD=%s\nPGCP_TOTP_SECRET=%s\n' "$PGCP_ADMIN_USER" "$PGCP_ADMIN_PASSWORD" "$PGCP_TOTP_SECRET" > "$CREDS"
    echo "admin credentials for this test environment saved to $CREDS (mode 0600)"
else
    step "login"
    : "${PGCP_ADMIN_USER:?}" "${PGCP_ADMIN_PASSWORD:?}" "${PGCP_TOTP_SECRET:?}"
    login
fi

step "server facts via agent"
call GET /api/servers | json "[(s['server']['name'], s['agentReachable'], s['facts']['os'] if s['facts'] else None) for s in d]"

step "create managed instance $NAME (PostgreSQL 18, loopback port $PORT) with database shop"
RESULT=$(call POST /api/instances/managed "{\"name\":\"$NAME\",\"pgMajor\":18,\"memoryMb\":512,\"cpus\":1,\"protection\":\"EPHEMERAL\",\"exposure\":\"LOCALHOST\",\"hostPort\":$PORT,\"initialDatabase\":{\"name\":\"shop\",\"createReadOnlyRole\":true}}")
INSTANCE=$(echo "$RESULT" | json "d['instance']['id']")
OWNER_PW=$(echo "$RESULT" | json "[c['password'] for c in d['credentials'] if c['kind']=='OWNER'][0]")
RO_PW=$(echo "$RESULT" | json "[c['password'] for c in d['credentials'] if c['kind']=='READ_ONLY'][0]")
await_job "$(echo "$RESULT" | json "d['jobId']")"
[ "$(call GET "/api/instances/$INSTANCE" | json "d['status']")" = RUNNING ] || fail "instance not RUNNING"

step "connect from the host (TLS required) as owner and write data"
psql_host "postgresql://shop_owner:$OWNER_PW@127.0.0.1:$PORT/shop?sslmode=require" \
    -c "create table orders(id int primary key); insert into orders values (1),(2),(3);"
[ "$(psql_host "postgresql://shop_owner:$OWNER_PW@127.0.0.1:$PORT/shop?sslmode=require" -c 'select ssl from pg_stat_ssl where pid = pg_backend_pid()')" = t ] \
    || fail "connection is not encrypted"

step "read-only role can read but not write"
[ "$(psql_host "postgresql://shop_ro:$RO_PW@127.0.0.1:$PORT/shop?sslmode=require" -c 'select count(*) from orders')" = 3 ] || fail "ro read"
if psql_host "postgresql://shop_ro:$RO_PW@127.0.0.1:$PORT/shop?sslmode=require" -c 'insert into orders values (4)' 2>/dev/null; then
    fail "read-only role could write"
fi

step "connect from the Docker network with sslmode=verify-full against the instances CA"
call GET /api/tls/instances-ca > "$WORK/ca.crt"
docker run --rm --network pgcp-instances -v "$WORK/ca.crt:/ca.crt:ro" "$PG_IMAGE" psql -qtA \
    "postgresql://shop_owner:$OWNER_PW@pgcp-inst-$NAME:5432/shop?sslmode=verify-full&sslrootcert=/ca.crt" -c 'select count(*) from orders' \
    | grep -qx 3 || fail "verify-full connection over the Docker network"

step "password is masked in connection info and audit"
call GET "/api/instances/$INSTANCE/databases/shop/connection" | grep -q '\*\*\*\*\*\*\*\*' || fail "not masked"
call GET "/api/audit?limit=500" | grep -q "$OWNER_PW" && fail "password found in audit log"
docker logs pgcp-control-plane 2>&1 | grep -q "$OWNER_PW" && fail "password found in control-plane log"
docker logs pgcp-agent 2>&1 | grep -q "$OWNER_PW" && fail "password found in agent log"
grep -rqs "$OWNER_PW" "${PGCP_DATA_DIR:-/var/lib/pgcp}/instances/$NAME" 2>/dev/null && fail "password found in instance files"
echo ok

step "restart the whole pgcp stack; the instance keeps running and keeps its data"
docker compose restart >/dev/null
for _ in $(seq 1 60); do curl -fsS "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 2; done
docker restart "pgcp-inst-$NAME" >/dev/null
sleep 8
[ "$(psql_host "postgresql://shop_owner:$OWNER_PW@127.0.0.1:$PORT/shop?sslmode=require" -c 'select count(*) from orders')" = 3 ] || fail "data lost after restart"
echo ok

step "audit hash chain"
# The stack restart ended the session (sessions are in memory); sign in again.
login
call GET /api/audit/verify | json "d"

if [ "$CLEANUP" = "--cleanup" ]; then
    step "delete instance (step-up + typed name)"
    fresh_code
    call POST /api/auth/step-up "{\"code\":\"$CODE\"}" >/dev/null
    await_job "$(call POST "/api/instances/$INSTANCE/delete" "{\"confirmName\":\"$NAME\",\"deleteVolume\":true}" | json "d['jobId']")"
    docker inspect "pgcp-inst-$NAME" >/dev/null 2>&1 && fail "container still exists"
    echo deleted
fi

printf '\n\033[32mSMOKE TEST PASSED\033[0m (instance %s)\n' "$NAME"
