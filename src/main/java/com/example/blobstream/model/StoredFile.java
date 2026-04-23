package com.example.blobstream.model;

import java.time.OffsetDateTime;

public record StoredFile(
        long id,
        String fileName,
        String contentType,
        String objectKey,
        long contentLength,
        long lobOid,
        OffsetDateTime createdAt
) {
}
