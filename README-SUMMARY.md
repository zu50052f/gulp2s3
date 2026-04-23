# General Upload of Line-based Payloads

## Purpose

Spring Boot 3 / Java 21 service that streams data from PostgreSQL to S3-compatible storage over plain HTTP or HTTPS without using the S3 SDK.

## Main flows

- Store uploaded files in PostgreSQL large objects (`oid`) and later stream them to S3.
- Generate CSV reports from allowed PostgreSQL tables and stream them directly to S3 without building the report in memory or on disk.

## Low-memory design

- Blob path reads PostgreSQL large objects as streams instead of loading BLOB data into memory.
- CSV path uses a forward-only JDBC cursor with configurable `fetchSize`.
- CSV rows are written line by line into bounded multipart buffers and uploaded to S3 in one pass.
- No local temp file is required for the generated report flow.

## S3 transport

- Uses raw HTTP requests, including S3 multipart upload endpoints.
- Uses manual AWS Signature Version 4 signing.
- Uses path-style object URLs: `/{bucket}/{objectKey}`.
- Works locally with MinIO in Docker Compose.

## Key endpoints

- `POST /api/files`
- `GET /api/files`
- `POST /api/files/{id}/transfer`
- `POST /api/reports/{tableName}/transfer`

## Local stack

- App: Spring Boot service
- Database: PostgreSQL 16
- Object storage: MinIO
- Tracing backend: Jaeger
- Metrics backend: Prometheus
- Dashboard UI: Grafana
- Init job: creates the target bucket automatically

## Important config

- `app.s3.*`: S3 endpoint, bucket, credentials, region, timeouts
- `app.report.fetch-size`: JDBC batch size for streaming report rows
- `app.report.pipe-buffer-bytes`: buffered writer size for CSV generation
- `app.report.multipart-part-size-bytes`: multipart upload part size for report streaming
- `app.report.allowed-tables`: whitelist for report-exportable tables
- `management.otlp.tracing.endpoint`: OTLP trace export target
- `management.tracing.sampling.probability`: trace sampling rate
- `REPORT_ROW_TARGET`: number of sample rows generated for the load profile
- `REPORT_VUS`, `REPORT_DURATION`, `REPORT_PAUSE_SECONDS`, `REPORT_REUSE_KEYS`: report-load pacing and object reuse controls
- `BLOB_VUS`, `BLOB_DURATION`, `BLOB_PAUSE_SECONDS`, `BLOB_START_TIME`, `FILE_SIZE_BYTES`: blob-load pacing and payload controls

## Example

```bash
docker compose up --build
curl -X POST "http://localhost:8080/api/reports/report_source_row/transfer?objectKey=reports/report_source_row.csv"
```

For the load test and dashboard:

```bash
docker compose --profile load up --build
```

Or:

```bash
./scripts/run-observability-stack.sh
```

Default behavior is a clean restart. To keep the current stack running and just issue `up`:

```bash
./scripts/run-observability-stack.sh --no-clean-start
```

## Files to read first

- [README.md](./README.md)
- [compose.yaml](./compose.yaml)
- [observability/prometheus.yml](./observability/prometheus.yml)
- [observability/grafana/dashboards/blob-stream-overview.json](./observability/grafana/dashboards/blob-stream-overview.json)
- [observability/load/loadtest.js](./observability/load/loadtest.js)
- [run-observability-stack.sh](./scripts/run-observability-stack.sh)
- [application.yml](./src/main/resources/application.yml)
- [DatabaseFileService.java](./src/main/java/com/example/blobstream/service/DatabaseFileService.java)
- [ReportStreamingService.java](./src/main/java/com/example/blobstream/service/ReportStreamingService.java)
- [S3HttpUploader.java](./src/main/java/com/example/blobstream/service/S3HttpUploader.java)
