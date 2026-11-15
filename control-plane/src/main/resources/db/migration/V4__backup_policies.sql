create table backup_policy (
    id uuid primary key, name text not null, revision bigint not null,
    template_secret_id bigint not null references secret(id),
    applied_revision bigint, agent_status jsonb, checked_at timestamptz,
    sync_error text, created_at timestamptz not null default now()
);
create table backup_policy_event (
    id uuid primary key, policy_id uuid not null references backup_policy(id),
    backup_id uuid not null unique, imported_at timestamptz not null default now()
);
alter table logical_backup add column policy_id uuid references backup_policy(id);
create index logical_backup_policy on logical_backup(policy_id,created_at desc);
