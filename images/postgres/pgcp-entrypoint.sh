#!/bin/sh
# Copies pgcp-provided files (mounted read-only at /etc/pgcp-src) into place with the ownership
# PostgreSQL insists on, then hands over to the official docker-entrypoint.sh unchanged.
set -eu

SRC=/etc/pgcp-src
DST=/etc/pgcp

if [ "$(id -u)" = "0" ] && [ -d "$SRC" ]; then
    install -d -o postgres -g postgres -m 0700 "$DST"
    install -o postgres -g postgres -m 0644 "$SRC/pg_hba.conf" "$DST/pg_hba.conf"
    install -o postgres -g postgres -m 0644 "$SRC/pg_ident.conf" "$DST/pg_ident.conf"
    install -o postgres -g postgres -m 0644 "$SRC/tls/server.crt" "$DST/server.crt"
    install -o postgres -g postgres -m 0600 "$SRC/tls/server.key" "$DST/server.key"
    # Only meaningful for initdb; the agent replaces it with a placeholder after the first healthy start.
    install -o postgres -g postgres -m 0600 "$SRC/superuser-password" "$DST/superuser-password"
fi

exec docker-entrypoint.sh "$@"
