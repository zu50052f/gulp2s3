\set ON_ERROR_STOP on

update report_source_row
set payload = substring(
    repeat(md5(id::text || '-payload-'), ((:payload_bytes::int + 31) / 32) + 1)
    from 1
    for :payload_bytes::int
)
where length(payload) <> :payload_bytes::int;

with bounds as (
    select
        greatest((select coalesce(max(id), 0) + 1 from report_source_row), 4) as start_id,
        :target::bigint as target_id
)
insert into report_source_row (account_no, customer_name, amount, created_at, payload)
select
    lpad(gs::text, 20, '0'),
    'Customer ' || gs,
    ((gs % 100000)::numeric / 100),
    current_timestamp - make_interval(secs => (gs % 86400)::int),
    substring(
        repeat(md5(gs::text || '-payload-'), ((:payload_bytes::int + 31) / 32) + 1)
        from 1
        for :payload_bytes::int
    )
from bounds
cross join generate_series(bounds.start_id, bounds.target_id) as gs
where bounds.start_id <= bounds.target_id;
