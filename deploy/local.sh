#!/usr/bin/env bash
# Local-only stack; never reads the VPS override or its environment file.
set -euo pipefail
cd "$(dirname "$0")"
REPO_DIR=$(cd .. && pwd -P)
export PGCP_DATA_DIR="$REPO_DIR/.local-data"

compose() {
    docker compose --project-name pgcp-local --env-file .env.local \
        -f compose.yml -f compose.local.yml "$@"
}

case "${1:-help}" in
    init)
        ./install.sh
        if [ ! -f .env.local ]; then
            (umask 077; cp .env.local.example .env.local)
        fi
        (umask 077; mkdir -p "$PGCP_DATA_DIR")
        echo 'Local configuration ready. Start with: ./local.sh up'
        ;;
    up)
        compose up -d --build --wait --wait-timeout 180
        echo 'Open http://localhost:8480 (or the port in deploy/.env.local).'
        echo 'Retrieve the initial setup token with: ./local.sh token'
        ;;
    token)
        compose exec -T control-plane cat /var/lib/pgcp-cp/setup-token
        ;;
    down|ps|logs|build|restart|config)
        compose "$@"
        ;;
    *)
        echo 'Usage: ./local.sh {init|up|token|down|ps|logs|build|restart|config} [arguments]'
        ;;
esac
