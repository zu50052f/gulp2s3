package com.example.blobstream.service;

import com.example.blobstream.config.ReportStreamingProperties;
import com.example.blobstream.model.ReportTransferResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public class ReportStreamingService {

    private static final String CSV_CONTENT_TYPE = "text/csv; charset=utf-8";
    private static final String ZIP_CONTENT_TYPE = "application/zip";
    private static final DateTimeFormatter OBJECT_KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DataSource dataSource;
    private final ReportStreamingProperties properties;
    private final S3HttpUploader s3HttpUploader;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    public ReportStreamingService(
            DataSource dataSource,
            ReportStreamingProperties properties,
            S3HttpUploader s3HttpUploader,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry
    ) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.s3HttpUploader = s3HttpUploader;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    public ReportTransferResponse streamTableAsCsvToS3(String tableName, String requestedObjectKey, boolean zip) {
        String normalizedTable = normalizeAndValidateTable(tableName);
        String format = zip ? "zip" : "csv";
        String objectKey = defaultObjectKey(normalizedTable, requestedObjectKey, zip);

        Observation observation = Observation.createNotStarted("blobstream.report.transfer", observationRegistry)
                .contextualName("report-transfer")
                .lowCardinalityKeyValue("flow", "report")
                .lowCardinalityKeyValue("format", format)
                .lowCardinalityKeyValue("table", normalizedTable)
                .lowCardinalityKeyValue("target", "s3");
        Timer.Sample timerSample = Timer.start(meterRegistry);
        String result = "success";
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            ReportTransferResponse response = streamTableAsCsvToS3Internal(normalizedTable, objectKey, zip);
            DistributionSummary.builder("blobstream.report.rows")
                    .baseUnit("rows")
                    .tag("format", format)
                    .tag("table", normalizedTable)
                    .register(meterRegistry)
                    .record(response.rowCount());
            DistributionSummary.builder("blobstream.report.bytes")
                    .baseUnit("bytes")
                    .tag("format", format)
                    .tag("table", normalizedTable)
                    .register(meterRegistry)
                    .record(response.byteCount());
            observation.event(Observation.Event.of("transfer.completed"));
            return response;
        } catch (RuntimeException ex) {
            result = "error";
            observation.event(Observation.Event.of("transfer.failed"));
            observation.error(ex);
            throw ex;
        } finally {
            observation.lowCardinalityKeyValue("result", result);
            observation.stop();
            Counter.builder("blobstream.report.transfer.requests")
                    .tag("format", format)
                    .tag("table", normalizedTable)
                    .tag("result", result)
                    .register(meterRegistry)
                    .increment();
            timerSample.stop(
                    Timer.builder("blobstream.report.transfer.duration")
                            .tag("format", format)
                            .tag("table", normalizedTable)
                            .tag("result", result)
                            .register(meterRegistry)
            );
        }
    }

    private ReportTransferResponse streamTableAsCsvToS3Internal(String normalizedTable, String objectKey, boolean zip) {
        AtomicLong rowCount = new AtomicLong();
        String format = zip ? "zip" : "csv";
        String contentType = zip ? ZIP_CONTENT_TYPE : CSV_CONTENT_TYPE;
        S3HttpUploader.MultipartUpload multipartUpload = s3HttpUploader.startMultipartUpload(objectKey, contentType);

        MultipartUploadOutputStream multipartOutputStream = new MultipartUploadOutputStream(
                s3HttpUploader,
                multipartUpload,
                properties.getMultipartPartSizeBytes()
        );
        ZipOutputStream zipOutputStream = null;
        OutputStream reportOutputStream = multipartOutputStream;
        if (zip) {
            zipOutputStream = new ZipOutputStream(multipartOutputStream, StandardCharsets.UTF_8);
            reportOutputStream = zipOutputStream;
        }
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(reportOutputStream, StandardCharsets.UTF_8),
                properties.getPipeBufferBytes()
        );

        boolean completed = false;
        try {
            if (zipOutputStream != null) {
                zipOutputStream.putNextEntry(new ZipEntry(normalizedTable + ".csv"));
            }
            streamTableAsCsv(normalizedTable, writer, rowCount);
            writer.flush();
            if (zipOutputStream != null) {
                zipOutputStream.closeEntry();
                zipOutputStream.finish();
            }

            int statusCode = multipartOutputStream.complete();
            completed = true;
            return new ReportTransferResponse(
                    normalizedTable,
                    format,
                    rowCount.get(),
                    multipartOutputStream.totalUploadedBytes(),
                    s3HttpUploader.bucket(),
                    objectKey,
                    statusCode
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write the CSV report for table " + normalizedTable + ".", ex);
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Ignore close noise while preserving the original failure.
            }
            if (!completed) {
                try {
                    s3HttpUploader.abortMultipartUpload(multipartUpload.objectKey(), multipartUpload.uploadId());
                } catch (RuntimeException ignored) {
                    // Best-effort abort to avoid orphaned multipart uploads.
                }
            }
        }
    }

    private String defaultObjectKey(String normalizedTable, String requestedObjectKey, boolean zip) {
        if (requestedObjectKey != null && !requestedObjectKey.isBlank()) {
            return requestedObjectKey;
        }

        String suffix = zip ? ".zip" : ".csv";
        return "reports/" + normalizedTable + "-" + OBJECT_KEY_TIMESTAMP.format(OffsetDateTime.now(ZoneOffset.UTC)) + suffix;
    }

    private void streamTableAsCsv(String tableName, BufferedWriter writer, AtomicLong rowCount) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);

            try (PreparedStatement statement = connection.prepareStatement(
                    "select * from " + tableName,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            )) {
                statement.setFetchSize(properties.getFetchSize());
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    writeHeader(writer, metaData);
                    while (resultSet.next()) {
                        writeRow(writer, resultSet, metaData.getColumnCount());
                        rowCount.incrementAndGet();
                    }
                    writer.flush();
                }
            }

            connection.commit();
        } catch (SQLException | IOException ex) {
            throw new IllegalStateException("Failed to stream table " + tableName + " as CSV.", ex);
        }
    }

    private void writeHeader(BufferedWriter writer, ResultSetMetaData metaData) throws SQLException, IOException {
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            if (index > 1) {
                writer.write(',');
            }
            writer.write(escapeCsv(metaData.getColumnLabel(index)));
        }
        writer.write('\n');
    }

    private void writeRow(BufferedWriter writer, ResultSet resultSet, int columnCount) throws SQLException, IOException {
        for (int index = 1; index <= columnCount; index++) {
            if (index > 1) {
                writer.write(',');
            }
            String value = resultSet.getString(index);
            writer.write(escapeCsv(value));
        }
        writer.write('\n');
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        boolean requiresQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!requiresQuotes) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String normalizeAndValidateTable(String tableName) {
        String normalizedTable = tableName.toLowerCase(Locale.ROOT);
        if (!normalizedTable.matches("[a-z_][a-z0-9_]*")) {
            throw new ResponseStatusException(BAD_REQUEST, "Table name must match [a-z_][a-z0-9_]*.");
        }

        Set<String> allowedTables = properties.getAllowedTables().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!allowedTables.contains(normalizedTable)) {
            throw new ResponseStatusException(BAD_REQUEST, "Table " + tableName + " is not enabled for streaming reports.");
        }
        return normalizedTable;
    }

    private static final class MultipartUploadOutputStream extends OutputStream {
        private final S3HttpUploader s3HttpUploader;
        private final S3HttpUploader.MultipartUpload multipartUpload;
        private final List<S3HttpUploader.UploadedPart> uploadedParts = new ArrayList<>();
        private final byte[] buffer;
        private int bufferedBytes;
        private int nextPartNumber = 1;
        private boolean closed;
        private boolean completed;
        private long totalUploadedBytes;

        private MultipartUploadOutputStream(
                S3HttpUploader s3HttpUploader,
                S3HttpUploader.MultipartUpload multipartUpload,
                int partSizeBytes
        ) {
            this.s3HttpUploader = s3HttpUploader;
            this.multipartUpload = multipartUpload;
            this.buffer = new byte[partSizeBytes];
        }

        @Override
        public void write(int value) throws IOException {
            ensureOpen();
            if (bufferedBytes == buffer.length) {
                uploadBufferedPart(false);
            }
            buffer[bufferedBytes++] = (byte) value;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureOpen();
            int remaining = length;
            int currentOffset = offset;
            while (remaining > 0) {
                if (bufferedBytes == buffer.length) {
                    uploadBufferedPart(false);
                }

                int bytesToCopy = Math.min(remaining, buffer.length - bufferedBytes);
                System.arraycopy(bytes, currentOffset, buffer, bufferedBytes, bytesToCopy);
                bufferedBytes += bytesToCopy;
                currentOffset += bytesToCopy;
                remaining -= bytesToCopy;
            }
        }

        @Override
        public void flush() {
            // BufferedWriter flushes into this stream; S3 parts are sent only on full part or completion.
        }

        @Override
        public void close() {
            closed = true;
        }

        private int complete() throws IOException {
            ensureNotCompleted();
            close();
            uploadBufferedPart(true);
            int statusCode = s3HttpUploader.completeMultipartUpload(
                    multipartUpload.objectKey(),
                    multipartUpload.uploadId(),
                    uploadedParts
            );
            completed = true;
            return statusCode;
        }

        private void uploadBufferedPart(boolean finalPart) throws IOException {
            if (bufferedBytes == 0 && !(finalPart && uploadedParts.isEmpty())) {
                return;
            }

            S3HttpUploader.UploadedPart uploadedPart = s3HttpUploader.uploadPart(
                    multipartUpload.objectKey(),
                    multipartUpload.uploadId(),
                    nextPartNumber++,
                    buffer,
                    bufferedBytes
            );
            uploadedParts.add(uploadedPart);
            bufferedBytes = 0;
            totalUploadedBytes += uploadedPart.size();
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Multipart upload stream is already closed.");
            }
            ensureNotCompleted();
        }

        private void ensureNotCompleted() throws IOException {
            if (completed) {
                throw new IOException("Multipart upload was already completed.");
            }
        }

        private long totalUploadedBytes() {
            return totalUploadedBytes;
        }
    }
}
