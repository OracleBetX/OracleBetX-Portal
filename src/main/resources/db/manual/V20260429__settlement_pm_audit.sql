create table if not exists settlement_pm_audit (
    id uuid primary key,
    settle_batch_id varchar(128),
    event_id varchar(128) not null,
    market_id varchar(128) not null,
    caller_selection_id varchar(64),
    pm_selection_id varchar(64),
    pm_confidence varchar(32),
    mode varchar(16) not null,
    decision varchar(32) not null,
    mismatch boolean not null default false,
    reason text,
    error_message text,
    actor varchar(128),
    created_at timestamptz not null default now()
);

create index if not exists idx_settlement_pm_audit_batch
    on settlement_pm_audit (settle_batch_id);

create index if not exists idx_settlement_pm_audit_event_market_created
    on settlement_pm_audit (event_id, market_id, created_at desc);

create index if not exists idx_settlement_pm_audit_decision_created
    on settlement_pm_audit (decision, created_at desc);
