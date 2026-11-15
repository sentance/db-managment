alter table backup_policy_event drop constraint backup_policy_event_backup_id_key;
alter table logical_restore add column retention_released boolean not null default false;

create table telegram_settings (
    id int primary key check(id=1), enabled boolean not null default false,
    token_secret_id bigint references secret(id), chat_id text not null default '', revision bigint not null default 1
);
insert into telegram_settings(id) values(1);
create table backup_alert (
    id uuid primary key, dedupe_key text not null unique, kind text not null, resource_id uuid not null,
    message text not null, active boolean not null, generation int not null,
    opened_at timestamptz not null default now(), changed_at timestamptz not null default now(), resolved_at timestamptz
);
create table notification_delivery (
    id uuid primary key, alert_id uuid not null references backup_alert(id), generation int not null,
    config_revision bigint not null, message text not null, status text not null default 'PENDING',
    attempts int not null default 0, next_attempt_at timestamptz not null default now(), claimed_at timestamptz,
    sent_at timestamptz, last_error text, created_at timestamptz not null default now(),
    unique(alert_id,generation)
);
create index notification_due on notification_delivery(status,next_attempt_at);
