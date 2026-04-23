create table if not exists stored_file (
    id bigserial primary key,
    file_name varchar(255) not null,
    content_type varchar(255) not null,
    object_key varchar(1024) not null unique,
    content_length bigint not null,
    lob_oid oid not null,
    created_at timestamptz not null default current_timestamp
);

create table if not exists report_source_row (
    id bigserial primary key,
    account_no varchar(64) not null,
    customer_name varchar(255) not null,
    amount numeric(18, 2) not null,
    created_at timestamptz not null default current_timestamp,
    payload text not null default ''
);

alter table if exists report_source_row
    add column if not exists payload text not null default '';
