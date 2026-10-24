-- pgcp metadata schema, Phase 1.
-- Secrets are stored only as AES-GCM ciphertext; the master key lives outside this database.
-- Table names avoid the pg_ prefix: unqualified pg_* names resolve to system catalogs first.

create table secret (
    id          bigserial primary key,
    purpose     text        not null,
    key_id      text        not null,
    nonce       bytea       not null,
    ciphertext  bytea       not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table admin_user (
    id               bigserial primary key,
    username         text        not null unique,
    password_hash    text        not null,
    totp_secret_id   bigint      references secret (id),
    totp_enabled     boolean     not null default false,
    totp_last_step   bigint      not null default 0,
    created_at       timestamptz not null default now(),
    last_login_at    timestamptz
);

create table totp_recovery_code (
    id            bigserial primary key,
    admin_user_id bigint      not null references admin_user (id) on delete cascade,
    code_hash     text        not null,
    used_at       timestamptz
);

create table server (
    id               uuid primary key,
    name             text        not null unique,
    agent_url        text        not null,
    hostname         text,
    os               text,
    kernel           text,
    cpu_cores        int,
    mem_total_bytes  bigint,
    docker_version   text,
    facts            jsonb,
    last_seen_at     timestamptz,
    created_at       timestamptz not null default now()
);

create table instance (
    id                   uuid primary key,
    server_id            uuid        not null references server (id),
    name                 text        not null,
    kind                 text        not null check (kind in ('MANAGED', 'EXTERNAL')),
    status               text        not null,
    status_message       text,
    pg_major             int,
    pg_version           text,
    image                text,
    libc                 text,
    container_name       text,
    host                 text        not null,
    port                 int         not null,
    ssl_mode             text        not null default 'require',
    bind_address         text,
    host_port            int,
    extra_networks       text[]      not null default '{}',
    external_mode        text check (external_mode in ('MONITOR_ONLY', 'MANAGE_OBJECTS')),
    protection           text        not null default 'STANDARD' check (protection in ('PRODUCTION', 'STANDARD', 'EPHEMERAL')),
    memory_mb            int,
    cpus                 numeric(6, 2),
    settings             jsonb       not null default '{}',
    maintenance_db       text        not null default 'postgres',
    admin_role           text        not null,
    admin_secret_id      bigint      references secret (id),
    superuser_secret_id  bigint      references secret (id),
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    check (kind = 'MANAGED' or external_mode is not null)
);

-- Names and published ports may be reused once an instance is deleted.
create unique index instance_name_active on instance (name) where status <> 'DELETED';
create unique index instance_host_port on instance (host_port) where host_port is not null and status <> 'DELETED';

create table managed_database (
    id           uuid primary key,
    instance_id  uuid        not null references instance (id),
    name         text        not null,
    owner_role   text        not null,
    created_at   timestamptz not null default now(),
    unique (instance_id, name)
);

create table managed_role (
    id           uuid primary key,
    instance_id  uuid        not null references instance (id),
    name         text        not null,
    kind         text        not null check (kind in ('OWNER', 'READ_WRITE', 'READ_ONLY', 'CUSTOM')),
    secret_id    bigint      references secret (id),
    locked       boolean     not null default false,
    created_at   timestamptz not null default now(),
    rotated_at   timestamptz,
    unique (instance_id, name)
);

create table job (
    id            uuid primary key,
    type          text        not null,
    status        text        not null check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    resource_key  text        not null,
    title         text        not null,
    params        jsonb       not null default '{}',
    result        jsonb,
    progress      int         not null default 0,
    message       text,
    error         text,
    attempts      int         not null default 0,
    created_by    text        not null,
    created_at    timestamptz not null default now(),
    started_at    timestamptz,
    finished_at   timestamptz
);

-- At most one active job per resource: conflicting operations are rejected instead of interleaved.
create unique index job_one_active_per_resource on job (resource_key) where status in ('PENDING', 'RUNNING');
create index job_pending on job (created_at) where status = 'PENDING';
create index job_created on job (created_at desc);

create table job_event (
    id       bigserial primary key,
    job_id   uuid        not null references job (id) on delete cascade,
    ts       timestamptz not null default now(),
    level    text        not null,
    message  text        not null
);

create index job_event_job on job_event (job_id, id);

create table audit_log (
    id           bigserial primary key,
    ts           timestamptz not null default now(),
    actor        text        not null,
    source_ip    text,
    action       text        not null,
    target_type  text,
    target_id    text,
    outcome      text        not null,
    details      jsonb       not null default '{}',
    prev_hash    bytea,
    hash         bytea       not null
);

create index audit_log_ts on audit_log (ts desc);

-- The audit log is append-only even for the application's own database role.
create function audit_log_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'audit_log is append-only';
end;
$$;

create trigger audit_log_no_update before update or delete on audit_log
    for each row execute function audit_log_immutable();
create trigger audit_log_no_truncate before truncate on audit_log
    for each statement execute function audit_log_immutable();

create table setting (
    key         text primary key,
    value       jsonb       not null,
    updated_at  timestamptz not null default now()
);
