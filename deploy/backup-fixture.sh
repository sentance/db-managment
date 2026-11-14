#!/usr/bin/env bash
# Operates only on a dedicated local fixture Compose project.
set -euo pipefail
cd "$(dirname "$0")"
SCRIPT_PATH="$PWD/backup-fixture.sh"
PROJECT=${PGCP_FIXTURE_PROJECT:-pgcp-fixture-backups}
if [[ ! "$PROJECT" =~ ^pgcp-fixture-[a-z0-9-]+$ ]]; then
    echo 'PGCP_FIXTURE_PROJECT must start with pgcp-fixture- and use lowercase letters, digits or hyphens.' >&2
    exit 1
fi
compose() { docker compose --project-name "$PROJECT" -f fixtures/compose.yml "$@"; }
backrest() { compose exec -T --user postgres postgres pgbackrest --stanza=fixture "$@"; }

case "${1:-help}" in
    up)
        compose up -d --build --wait --wait-timeout 90
        backrest stanza-create
        backrest check
        ;;
    backup) backrest --type=full backup ;;
    info) backrest --output=json info ;;
    container) compose ps -q postgres ;;
    down) compose down --volumes --remove-orphans ;;
    smoke)
        export PGCP_FIXTURE_PROJECT="pgcp-fixture-smoke-$(date +%s)-$$"
        PROJECT=$PGCP_FIXTURE_PROJECT
        trap 'compose down --volumes --remove-orphans' EXIT
        "$SCRIPT_PATH" up
        "$SCRIPT_PATH" backup
        "$SCRIPT_PATH" info | python3 -c 'import json,sys; s=json.load(sys.stdin)[0]; assert s["status"]["code"]==0; assert any(not b.get("error",False) for b in s["backup"]); print("Fixture backup catalogue verified")'
        compose exec -T --user postgres postgres psql -X -d fixture -Atc 'select count(*) from orders' | grep -qx 3
        ;;
    *) echo 'Usage: deploy/backup-fixture.sh {up|backup|info|container|down|smoke}' ;;
esac
