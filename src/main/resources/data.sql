insert into report_source_row (account_no, customer_name, amount, created_at, payload)
select *
from (
    values
        ('40817810000000000001', 'Alice Example', 1250.10, current_timestamp - interval '2 day', 'sample-report-payload-alpha'),
        ('40817810000000000002', 'Bob Example', 502.45, current_timestamp - interval '1 day', 'sample-report-payload-beta'),
        ('40817810000000000003', 'Charlie Example', 9900.00, current_timestamp, 'sample-report-payload-gamma')
) as seed(account_no, customer_name, amount, created_at, payload)
where not exists (
    select 1
    from report_source_row
);
