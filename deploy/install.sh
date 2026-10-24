#!/usr/bin/env bash
# Prepares a pgcp deployment directory: secrets, mutual-TLS certificates and .env.
# Idempotent: existing secrets and certificates are never overwritten. Touches nothing outside this directory.
set -euo pipefail
cd "$(dirname "$0")"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required command: $1" >&2; exit 1; }; }
need docker
need openssl
docker compose version >/dev/null

umask 077
mkdir -p secrets certs
chmod 700 secrets certs

# Files inside are world-readable so container users (postgres, pgcp) can read their bind mounts;
# the 0700 parent directories keep other host users out.
if [ ! -s secrets/master.key ]; then
    openssl rand -base64 32 > secrets/master.key
    echo "generated secrets/master.key  -> BACK THIS UP OFFLINE (password manager). Without it stored credentials cannot be decrypted."
fi
if [ ! -s secrets/meta_password ]; then
    openssl rand -hex 24 > secrets/meta_password
    echo "generated secrets/meta_password"
fi
chmod 644 secrets/master.key secrets/meta_password

if [ ! -s certs/ca.key ]; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
        -subj "/CN=pgcp internal CA" -keyout certs/ca.key -out certs/ca.crt 2>/dev/null
    echo "generated internal CA"
fi

issue() { # name, subject, extensions
    local name=$1 subject=$2 ext=$3
    if [ -s "certs/$name.crt" ]; then return; fi
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "$subject" \
        -keyout "certs/$name.key" -out "certs/$name.csr" 2>/dev/null
    openssl x509 -req -in "certs/$name.csr" -CA certs/ca.crt -CAkey certs/ca.key -CAcreateserial \
        -days 825 -sha256 -extfile <(printf '%s\n' "$ext") -out "certs/$name.crt" 2>/dev/null
    rm -f "certs/$name.csr"
    echo "issued certs/$name.crt"
}
issue agent "/CN=pgcp-agent" "subjectAltName=DNS:pgcp-agent,DNS:localhost
extendedKeyUsage=serverAuth
keyUsage=digitalSignature"
issue control-plane "/CN=pgcp-control-plane" "extendedKeyUsage=clientAuth
keyUsage=digitalSignature"
chmod 600 certs/ca.key
chmod 644 certs/ca.crt certs/agent.crt certs/agent.key certs/control-plane.crt certs/control-plane.key

if [ ! -f .env ]; then
    cp .env.example .env
    echo "created .env from .env.example (review PGCP_PANEL_BIND / PGCP_PUBLIC_HOST)"
fi

cat <<'MSG'

Next steps:
  docker compose up -d --build
  docker exec pgcp-control-plane cat /var/lib/pgcp-cp/setup-token   # one-time setup token
Then open the panel (through your HTTPS reverse proxy) and complete setup with an authenticator app.
MSG
