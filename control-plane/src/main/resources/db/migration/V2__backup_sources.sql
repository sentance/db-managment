-- Existing repositories stay externally owned. Only normalised catalogue snapshots are stored.
create table backup_source (
    instance_id uuid primary key references instance(id),
    binding_id uuid not null unique,
    container_name text not null,
    container_id text not null check (container_id ~ '^[a-f0-9]{64}$'),
    stanza text not null check (stanza ~ '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$'),
    stale_after_hours int not null check (stale_after_hours between 1 and 8760),
    inspection_state text not null default 'NEVER',
    checked_at timestamptz,
    last_success_at timestamptz,
    error text,
    snapshot jsonb,
    updated_at timestamptz not null default now()
);
