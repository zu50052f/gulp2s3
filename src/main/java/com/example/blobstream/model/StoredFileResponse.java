package com.example.blobstream.model;

import java.time.OffsetDateTime;

public record StoredFileResponse(
        long id,
        String fileName,
        String contentType,
        String objectKey,
        long contentLength,
        OffsetDateTime createdAt
) {
    public static StoredFileResponse from(StoredFile storedFile) {
        return new StoredFileResponse(
                storedFile.id(),
                storedFile.fileName(),
                storedFile.contentType(),
                storedFile.objectKey(),
                storedFile.contentLength(),
                storedFile.createdAt()
        );
    }
}
