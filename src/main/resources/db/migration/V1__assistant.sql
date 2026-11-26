create extension if not exists vector with schema public;

create table scheduled_tasks (
    task_name text not null, task_instance text not null, task_data bytea,
    execution_time timestamptz not null, picked boolean not null, picked_by text,
    last_success timestamptz, last_failure timestamptz, consecutive_failures integer,
    last_heartbeat timestamptz, version bigint not null, priority smallint,
    primary key (task_name, task_instance)
);
create index scheduled_due on scheduled_tasks (execution_time);
create index scheduled_heartbeat on scheduled_tasks (last_heartbeat);

create table tasks (
    id text primary key, type text not null, input text not null, scope text,
    status text not null, step text not null, attempts integer not null default 0,
    result_type text, result_id text, error text, created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index tasks_active on tasks (type, scope) where status in ('QUEUED', 'RUNNING');
create table request_keys (
    owner text not null, operation text not null, key text not null, payload_hash text not null,
    response text not null, primary key (owner, operation, key)
);

create table documents (
    id text primary key, path text not null, kind text not null, version text,
    status text not null, reason text, indexed_profile text, updated_at timestamptz not null default now()
);
create unique index documents_live_path on documents (path) where status <> 'DELETED';
create table index_settings (singleton boolean primary key default true check (singleton), profile text not null);
create table fragments (
    document_id text not null references documents, version text not null, profile text not null,
    ordinal integer not null, anchor text not null, excerpt text not null, context text not null,
    terms text not null, embedding vector not null,
    primary key (document_id, version, profile, ordinal)
);
create index fragments_profile on fragments (profile);
create table citations (
    id text primary key, document_id text not null references documents, version text not null,
    anchor text not null, excerpt text not null, context text not null
);
create table answers (id text primary key, question text not null, content text not null);
create table bookmarks (
    owner text not null, document_id text not null references documents,
    version text not null, anchor text not null, ratio double precision not null,
    excerpt text not null, context text not null, revision integer not null,
    primary key (owner, document_id)
);

create table threads (id text primary key, subject text not null, updated_at timestamptz not null default now());
create table mail_messages (
    seq bigint generated always as identity unique,
    id text primary key, thread_id text not null references threads,
    header_id text, fingerprint text not null, sender text not null, reply_to text not null,
    subject text not null, body text not null, sent_at timestamptz not null,
    direction text not null default 'IN'
);
create index mail_header_id on mail_messages (header_id);
create index mail_fingerprint on mail_messages (fingerprint);
create index mail_thread_order on mail_messages (thread_id, seq);
create table mail_deliveries (
    account text not null, folder text not null, uid_validity bigint not null, uid bigint not null,
    message_id text not null references mail_messages,
    primary key (account, folder, uid_validity, uid)
);
create table mail_cursor (
    account text not null, folder text not null, uid_validity bigint not null, last_uid bigint not null,
    primary key (account, folder)
);
create table drafts (
    id text primary key, task_id text not null unique references tasks, thread_id text not null references threads,
    message_id text not null references mail_messages, revision integer not null,
    status text not null, content text not null, citation_ids text not null, evidence_gap boolean not null,
    updated_at timestamptz not null default now()
);
create table sends (
    id text primary key, draft_id text not null references drafts, revision integer not null,
    status text not null, content text not null, message_id text not null unique,
    reply_header text, error text, partial boolean not null default false, updated_at timestamptz not null default now(),
    unique (draft_id, revision)
);
