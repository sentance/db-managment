# Managed PostgreSQL image: official Debian-based postgres + pgBackRest from apt.postgresql.org.
# The official image already has the PGDG apt repository configured, so pgBackRest comes from the
# same trusted source as PostgreSQL itself.
ARG PG_MAJOR=18
FROM postgres:${PG_MAJOR}-trixie

# Pinned to what apt.postgresql.org ships for trixie; bump deliberately after testing.
ARG PGBACKREST_VERSION=2.59.3

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends "pgbackrest=${PGBACKREST_VERSION}-*"; \
    rm -rf /var/lib/apt/lists/*; \
    install -d -o postgres -g postgres -m 0750 /var/log/pgbackrest /var/lib/pgbackrest /var/spool/pgbackrest; \
    install -d -o postgres -g postgres -m 0750 /etc/pgbackrest; \
    pgbackrest version

LABEL dev.pgcp.image="postgres" \
      dev.pgcp.pgbackrest-version="${PGBACKREST_VERSION}"

COPY --chmod=0755 pgcp-entrypoint.sh /usr/local/bin/pgcp-entrypoint.sh
ENTRYPOINT ["pgcp-entrypoint.sh"]
CMD ["postgres"]
