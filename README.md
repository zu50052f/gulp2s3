# General Upload of Line-based Payloads

Java 21 / Spring Boot service that stores incoming files as PostgreSQL large objects and streams them directly to an S3-compatible endpoint over plain HTTP or HTTPS. It also has a second path that streams CSV reports generated from database tables straight to S3 without materializing the report in memory or on disk. No AWS SDK is used.

Short overview: [README-SUMMARY.md](./README-SUMMARY.md)

## What it does

- Accepts a multipart upload and writes it into PostgreSQL as a large object (`oid`) inside a transaction.
- Stores only metadata plus the large-object OID in the `stored_file` table.
- Reads the large object back as an `InputStream` and streams it to S3 over raw HTTP, using fixed `PUT` for small objects and multipart upload for larger ones.
- Streams CSV reports from database rows directly to S3 with a JDBC cursor and one-pass multipart upload over raw HTTP, either as plain CSV or as a ZIP archive containing the CSV.
- Runs locally with Docker Compose using PostgreSQL and MinIO.

## Endpoints

- `POST /api/files` stores a multipart file in PostgreSQL.
- `GET /api/files` lists stored files.
- `POST /api/files/{id}/transfer` streams one stored file from PostgreSQL to S3.
- `POST /api/reports/{tableName}/transfer` streams `SELECT * FROM {tableName}` as CSV directly to S3, or as a ZIP when `zip=true`.
- `GET /actuator/prometheus` exposes Prometheus metrics.
- `GET /actuator/health` exposes service health.

## Run

```bash
docker compose up --build
```

Service URLs:

- App: `http://localhost:8080`
- MinIO API: `http://localhost:9000`
- MinIO console: `http://localhost:9001`
- Jaeger UI: `http://localhost:16686`
- Prometheus UI: `http://localhost:9090`
- Grafana UI: `http://localhost:3000` (`admin` / `admin`)

## Example

Store a file in PostgreSQL:

```bash
curl -X POST \
  -F "file=@./my-file.bin" \
  -F "objectKey=inbox/my-file.bin" \
  http://localhost:8080/api/files
```

List stored files:

```bash
curl http://localhost:8080/api/files
```

Transfer file id `1` to S3:

```bash
curl -X POST http://localhost:8080/api/files/1/transfer
```

Stream a CSV report generated on the fly from the sample table:

```bash
curl -X POST \
  "http://localhost:8080/api/reports/report_source_row/transfer?objectKey=reports/report_source_row.csv"
```

Stream the same report as a ZIP archive containing `report_source_row.csv`:

```bash
curl -X POST \
  "http://localhost:8080/api/reports/report_source_row/transfer?zip=true&objectKey=reports/report_source_row.zip"
```

That path:

- opens a forward-only JDBC cursor
- fetches rows in batches instead of loading all rows
- writes CSV line by line into multipart-sized buffers
- optionally wraps the CSV stream in a ZIP entry on the fly
- uploads each full buffer as an S3 multipart part
- completes the multipart upload after the final partial part

## Load Test

Run the full stack with Grafana and the optional load profile:

```bash
docker compose --profile load up --build
```

Or use the helper script:

```bash
./scripts/run-observability-stack.sh
```

By default the script runs `docker compose down --remove-orphans --volumes` first, then starts the stack again. To skip the teardown:

```bash
./scripts/run-observability-stack.sh --no-clean-start
```

What the load profile does:

- starts Grafana with a provisioned dashboard
- deletes previous load-test objects from MinIO under the configured load prefixes
- seeds `report_source_row` up to `REPORT_ROW_TARGET` rows using PostgreSQL `generate_series`
- runs a k6 load test that exercises all three:
- the plain CSV report streaming path
- the ZIP-compressed report streaming path
- the PostgreSQL large-object to S3 transfer path

Default load-related environment values:

- `REPORT_ROW_TARGET=513500`
- `REPORT_PAYLOAD_BYTES=4096`
- `REPORT_VUS=1`
- `REPORT_DURATION=5m`
- `REPORT_PAUSE_SECONDS=5`
- `REPORT_TIMEOUT=20m`
- `REPORT_REUSE_KEYS=true`
- `REPORT_ZIP_VUS=1`
- `REPORT_ZIP_DURATION=5m`
- `REPORT_ZIP_START_TIME=30s`
- `REPORT_ZIP_PAUSE_SECONDS=5`
- `REPORT_ZIP_TIMEOUT=20m`
- `BLOB_VUS=1`
- `BLOB_DURATION=5m`
- `BLOB_PAUSE_SECONDS=5`
- `BLOB_TIMEOUT=20m`
- `BLOB_START_TIME=60s`
- `BLOB_SEED_SIZE_BYTES=2147483648`
- `REPORT_JITTER_SECONDS=2`
- `REPORT_ZIP_JITTER_SECONDS=2`
- `BLOB_JITTER_SECONDS=2`
- `REPORT_INITIAL_JITTER_SECONDS=3`
- `REPORT_ZIP_INITIAL_JITTER_SECONDS=3`
- `BLOB_INITIAL_JITTER_SECONDS=3`
- `FILE_SIZE_BYTES=2097152`

With the default load profile, `report_source_row` is widened with a `payload` column and seeded to roughly a `2 GiB` CSV. The exact size is approximate because the non-payload columns and CSV header add some extra bytes on top.

The default giant-object profile now has two phases in one run:

- a short staggered start, so the plain CSV path begins first, then ZIP, then blob
- an overlap phase, where all three continue running together for the rest of the 5-minute window

Small random jitter is added both before the first request and between iterations, so the three long-running flows do not stay perfectly synchronized.

The load profile also clears the configured load-test prefixes in MinIO before each run, so old load artifacts do not pile up across restarts on a small Docker host.

To tune the generated report size, adjust both:

- `REPORT_ROW_TARGET` for the number of rows
- `REPORT_PAYLOAD_BYTES` for the per-row CSV payload width

That path streams rows directly from PostgreSQL into S3 multipart uploads and is the better fit for validating large generated exports under a small heap.

Provisioned dashboard:

- `Blob Stream / Blob Stream Overview`

Useful panels on the dashboard:

- resident memory and heap usage
- HTTP request rate and error rate
- report/blob/S3 transfer durations
- rows per second and bytes per second
- GC pause and live thread count

## Notes

- The local Compose stack uses MinIO because it speaks the S3 API over HTTP and is easy to run locally.
- The service signs requests with AWS Signature Version 4 manually.
- The object upload path is path-style: `/{bucket}/{objectKey}`.
- The report upload path uses S3 multipart upload over raw HTTP, without the AWS SDK.
- Tables allowed for the report endpoint are controlled by `app.report.allowed-tables` in [application.yml](./src/main/resources/application.yml).
- The bundled sample report source table is `report_source_row`; point the same endpoint at your own large table after adding it to the whitelist.
- Traces are exported with OpenTelemetry over OTLP to Jaeger.
- Metrics are exposed in Prometheus format at `/actuator/prometheus` and scraped by the bundled Prometheus container.
- Custom observations and metrics are emitted for file store, blob transfer, report transfer, and S3 PUT operations.
- Grafana is provisioned from files under [observability/grafana](./observability/grafana).
- The load test script and report seeding assets live under [observability/load](./observability/load).
- One-command startup helper: [run-observability-stack.sh](./scripts/run-observability-stack.sh).
