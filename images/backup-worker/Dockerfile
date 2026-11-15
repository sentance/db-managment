ARG PG_MAJOR=18
FROM postgres:${PG_MAJOR}-trixie
RUN apt-get update && apt-get install -y --no-install-recommends age awscli python3 python3-psycopg2 \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=0755 worker.py /opt/pgcp/worker.py
ENTRYPOINT ["python3", "/opt/pgcp/worker.py"]
