#!/bin/sh
set -eu

PGHOST="${PGHOST:-postgres}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-blobstream}"
PGUSER="${PGUSER:-blobstream}"
PGPASSWORD="${PGPASSWORD:-blobstream}"
REPORT_ROW_TARGET="${REPORT_ROW_TARGET:-513500}"
REPORT_PAYLOAD_BYTES="${REPORT_PAYLOAD_BYTES:-4096}"
POSTGRES_WAIT_TIMEOUT_SECONDS="${POSTGRES_WAIT_TIMEOUT_SECONDS:-120}"
export PGPASSWORD

DB_URI="postgresql://${PGUSER}:${PGPASSWORD}@${PGHOST}:${PGPORT}/${PGDATABASE}"

echo "Waiting for PostgreSQL to accept connections..."
STARTED_AT="$(date +%s)"
until psql "$DB_URI" -Atqc "select 1" >/dev/null 2>&1; do
  NOW="$(date +%s)"
  if [ $((NOW - STARTED_AT)) -ge "$POSTGRES_WAIT_TIMEOUT_SECONDS" ]; then
    echo "PostgreSQL did not become reachable within ${POSTGRES_WAIT_TIMEOUT_SECONDS}s."
    exit 1
  fi
  sleep 2
done

echo "Ensuring report_source_row exists..."
psql "$DB_URI" <<'SQL'
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
SQL

CURRENT_COUNT="$(psql "$DB_URI" -Atqc "select count(*) from report_source_row")"
PAYLOAD_MISMATCH_COUNT="$(psql "$DB_URI" -Atqc "select count(*) from report_source_row where length(payload) <> ${REPORT_PAYLOAD_BYTES}")"
echo "Current row count: ${CURRENT_COUNT}"
echo "Rows needing payload resize: ${PAYLOAD_MISMATCH_COUNT}"

if [ "${CURRENT_COUNT}" -ge "${REPORT_ROW_TARGET}" ] && [ "${PAYLOAD_MISMATCH_COUNT}" -eq 0 ]; then
  echo "Target row count ${REPORT_ROW_TARGET} with payload size ${REPORT_PAYLOAD_BYTES} already satisfied."
  exit 0
fi

echo "Seeding report_source_row up to ${REPORT_ROW_TARGET} rows with payload size ${REPORT_PAYLOAD_BYTES} bytes..."
psql "$DB_URI" -v target="${REPORT_ROW_TARGET}" -v payload_bytes="${REPORT_PAYLOAD_BYTES}" -f /scripts/seed-report-source.sql
echo "Seed complete."
