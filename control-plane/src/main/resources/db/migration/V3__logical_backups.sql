create table backup_destination (
    id uuid primary key, name text not null unique, endpoint text not null,
    region text not null, bucket text not null, prefix text not null unique, recipient text not null,
    credentials_secret_id bigint not null references secret(id),
    identity_secret_id bigint not null references secret(id),
    status text not null default 'UNTESTED', tested_at timestamptz, created_at timestamptz not null default now()
);
create table logical_backup (
    id uuid primary key, instance_id uuid not null references instance(id), database_name text not null,
    destination_id uuid not null references backup_destination(id), container_id text not null,
    pg_major int not null, status text not null default 'PENDING', job_id uuid,
    result jsonb, verified_at timestamptz, created_at timestamptz not null default now()
);
create table logical_restore (
    id uuid primary key, backup_id uuid not null references logical_backup(id),
    target_name text not null, database_name text not null, owner_role text not null,
    target_instance_id uuid references instance(id), job_id uuid, status text not null default 'PENDING',
    result jsonb, created_at timestamptz not null default now()
);
create unique index logical_restore_active_name on logical_restore(target_name) where status in ('PENDING','RUNNING');
