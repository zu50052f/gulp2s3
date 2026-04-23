package com.example.blobstream.service;

import com.example.blobstream.model.StoredFile;
import com.example.blobstream.model.TransferResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import com.example.blobstream.repository.StoredFileRepository;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class DatabaseFileService {

    private final DataSource dataSource;
    private final StoredFileRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final S3HttpUploader s3HttpUploader;
    private final ObservationRegistry observationRegistry;
    private final Counter storeRequestsSuccess;
    private final Counter storeRequestsError;
    private final Timer storeDurationSuccess;
    private final Timer storeDurationError;
    private final DistributionSummary storeBytes;
    private final Counter transferRequestsSuccess;
    private final Counter transferRequestsError;
    private final Timer transferDurationSuccess;
    private final Timer transferDurationError;
    private final DistributionSummary transferBytes;

    public DatabaseFileService(
            DataSource dataSource,
            StoredFileRepository repository,
            TransactionTemplate transactionTemplate,
            S3HttpUploader s3HttpUploader,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry
    ) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.s3HttpUploader = s3HttpUploader;
        this.observationRegistry = observationRegistry;
        this.storeRequestsSuccess = Counter.builder("blobstream.file.store.requests")
                .tag("result", "success")
                .register(meterRegistry);
        this.storeRequestsError = Counter.builder("blobstream.file.store.requests")
                .tag("result", "error")
                .register(meterRegistry);
        this.storeDurationSuccess = Timer.builder("blobstream.file.store.duration")
                .tag("result", "success")
                .register(meterRegistry);
        this.storeDurationError = Timer.builder("blobstream.file.store.duration")
                .tag("result", "error")
                .register(meterRegistry);
        this.storeBytes = DistributionSummary.builder("blobstream.file.store.bytes")
                .baseUnit("bytes")
                .register(meterRegistry);
        this.transferRequestsSuccess = Counter.builder("blobstream.file.transfer.requests")
                .tag("result", "success")
                .register(meterRegistry);
        this.transferRequestsError = Counter.builder("blobstream.file.transfer.requests")
                .tag("result", "error")
                .register(meterRegistry);
        this.transferDurationSuccess = Timer.builder("blobstream.file.transfer.duration")
                .tag("result", "success")
                .register(meterRegistry);
        this.transferDurationError = Timer.builder("blobstream.file.transfer.duration")
                .tag("result", "error")
                .register(meterRegistry);
        this.transferBytes = DistributionSummary.builder("blobstream.file.transfer.bytes")
                .baseUnit("bytes")
                .register(meterRegistry);
    }

    public StoredFile store(MultipartFile multipartFile, String requestedObjectKey) {
        Observation observation = Observation.createNotStarted("blobstream.file.store", observationRegistry)
                .contextualName("store-file-in-postgresql")
                .lowCardinalityKeyValue("flow", "blob")
                .lowCardinalityKeyValue("storage", "postgres-large-object");
        Timer.Sample timerSample = Timer.start();
        String result = "success";
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            StoredFile storedFile = Objects.requireNonNull(
                    transactionTemplate.execute(status -> storeInsideTransaction(multipartFile, requestedObjectKey)),
                    "Failed to store the multipart file in PostgreSQL."
            );
            storeBytes.record(storedFile.contentLength());
            return storedFile;
        } catch (RuntimeException ex) {
            result = "error";
            observation.error(ex);
            throw ex;
        } finally {
            observation.lowCardinalityKeyValue("result", result);
            observation.stop();
            ("success".equals(result) ? storeRequestsSuccess : storeRequestsError).increment();
            timerSample.stop("success".equals(result) ? storeDurationSuccess : storeDurationError);
        }
    }

    public List<StoredFile> listFiles() {
        return repository.findAll();
    }

    public TransferResponse transferToS3(long id) {
        Observation observation = Observation.createNotStarted("blobstream.file.transfer", observationRegistry)
                .contextualName("blob-transfer")
                .lowCardinalityKeyValue("flow", "blob")
                .lowCardinalityKeyValue("target", "s3");
        Timer.Sample timerSample = Timer.start();
        String result = "success";
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            TransferResponse response = Objects.requireNonNull(
                    transactionTemplate.execute(status -> transferInsideTransaction(id)),
                    "Failed to transfer the file to S3."
            );
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
            ("success".equals(result) ? transferRequestsSuccess : transferRequestsError).increment();
            timerSample.stop("success".equals(result) ? transferDurationSuccess : transferDurationError);
        }
    }

    private StoredFile storeInsideTransaction(MultipartFile multipartFile, String requestedObjectKey) {
        String fileName = multipartFile.getOriginalFilename() == null || multipartFile.getOriginalFilename().isBlank()
                ? "blob.bin"
                : multipartFile.getOriginalFilename();
        String contentType = multipartFile.getContentType() == null || multipartFile.getContentType().isBlank()
                ? "application/octet-stream"
                : multipartFile.getContentType();
        String objectKey = requestedObjectKey == null || requestedObjectKey.isBlank()
                ? UUID.randomUUID() + "-" + sanitizeFileName(fileName)
                : requestedObjectKey;

        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PGConnection pgConnection = connection.unwrap(PGConnection.class);
            LargeObjectManager largeObjectManager = pgConnection.getLargeObjectAPI();
            long oid = largeObjectManager.createLO();

            try {
                LargeObject largeObject = largeObjectManager.open(oid, LargeObjectManager.WRITE);
                try (InputStream inputStream = multipartFile.getInputStream();
                     OutputStream outputStream = largeObject.getOutputStream()) {
                    inputStream.transferTo(outputStream);
                } finally {
                    largeObject.close();
                }

                return repository.insert(fileName, contentType, objectKey, multipartFile.getSize(), oid);
            } catch (IOException | RuntimeException ex) {
                deleteLargeObjectQuietly(largeObjectManager, oid);
                throw ex;
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(
                    CONFLICT,
                    "Object key '" + objectKey + "' already exists. Use a different objectKey.",
                    ex
            );
        } catch (IOException | SQLException ex) {
            throw new DatabaseStorageException("Failed to write the file into PostgreSQL.", ex);
        } catch (RuntimeException ex) {
            throw new DatabaseStorageException("Failed to persist file metadata in PostgreSQL.", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private TransferResponse transferInsideTransaction(long id) {
        StoredFile storedFile = repository.findById(id)
                .orElseThrow(() -> new StoredFileNotFoundException(id));

        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PGConnection pgConnection = connection.unwrap(PGConnection.class);
            LargeObjectManager largeObjectManager = pgConnection.getLargeObjectAPI();
            LargeObject largeObject = largeObjectManager.open(storedFile.lobOid(), LargeObjectManager.READ);

            int statusCode;
            try (InputStream inputStream = largeObject.getInputStream()) {
                if (s3HttpUploader.shouldUseMultipart(storedFile.contentLength())) {
                    statusCode = s3HttpUploader.uploadMultipart(
                            storedFile.objectKey(),
                            storedFile.contentType(),
                            inputStream
                    );
                } else {
                    statusCode = s3HttpUploader.upload(
                            storedFile.objectKey(),
                            storedFile.contentType(),
                            storedFile.contentLength(),
                            inputStream
                    );
                }
            } finally {
                largeObject.close();
            }

            transferBytes.record(storedFile.contentLength());
            return new TransferResponse(id, s3HttpUploader.bucket(), storedFile.objectKey(), statusCode);
        } catch (IOException | SQLException ex) {
            throw new DatabaseStorageException("Failed to stream the large object from PostgreSQL.", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void deleteLargeObjectQuietly(LargeObjectManager largeObjectManager, long oid) {
        try {
            largeObjectManager.delete(oid);
        } catch (SQLException ignored) {
            // Ignore cleanup failures because the original exception is more important to surface.
        }
    }
}
