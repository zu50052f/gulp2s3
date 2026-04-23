package com.example.blobstream.service;

import com.example.blobstream.config.BlobSeedProperties;
import com.example.blobstream.model.StoredFile;
import com.example.blobstream.repository.StoredFileRepository;
import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "app.seed-blob", name = "enabled", havingValue = "true")
public class BlobSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BlobSeedRunner.class);

    private final DataSource dataSource;
    private final StoredFileRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final BlobSeedProperties properties;
    private final ApplicationContext applicationContext;

    public BlobSeedRunner(
            DataSource dataSource,
            StoredFileRepository repository,
            TransactionTemplate transactionTemplate,
            BlobSeedProperties properties,
            ApplicationContext applicationContext
    ) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<StoredFile> existing = repository.findByObjectKey(properties.getObjectKey());
        StoredFile storedFile;
        if (existing.isPresent()) {
            storedFile = existing.get();
            log.info(
                    "Reusing seeded blob objectKey={} id={} sizeBytes={}",
                    storedFile.objectKey(),
                    storedFile.id(),
                    storedFile.contentLength()
            );
        } else {
            storedFile = transactionTemplate.execute(status -> seedInsideTransaction());
            if (storedFile == null) {
                throw new IllegalStateException("Blob seed transaction completed without creating a file.");
            }
            log.info(
                    "Created seeded blob objectKey={} id={} sizeBytes={}",
                    storedFile.objectKey(),
                    storedFile.id(),
                    storedFile.contentLength()
            );
        }

        if (properties.isExitAfterSeed()) {
            int exitCode = org.springframework.boot.SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }

    private StoredFile seedInsideTransaction() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            PGConnection pgConnection = connection.unwrap(PGConnection.class);
            LargeObjectManager largeObjectManager = pgConnection.getLargeObjectAPI();
            long oid = largeObjectManager.createLO();
            byte[] chunk = new byte[properties.getChunkSizeBytes()];

            try {
                LargeObject largeObject = largeObjectManager.open(oid, LargeObjectManager.WRITE);
                try (largeObject) {
                    long remaining = properties.getSizeBytes();
                    while (remaining > 0) {
                        int bytesToWrite = (int) Math.min(chunk.length, remaining);
                        largeObject.write(chunk, 0, bytesToWrite);
                        remaining -= bytesToWrite;
                    }
                }

                return repository.insert(
                        properties.getFileName(),
                        properties.getContentType(),
                        properties.getObjectKey(),
                        properties.getSizeBytes(),
                        oid
                );
            } catch (RuntimeException ex) {
                deleteLargeObjectQuietly(largeObjectManager, oid);
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to seed large PostgreSQL object.", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void deleteLargeObjectQuietly(LargeObjectManager largeObjectManager, long oid) {
        try {
            largeObjectManager.delete(oid);
        } catch (SQLException ignored) {
            // Ignore cleanup failures because the original exception is more important.
        }
    }
}
