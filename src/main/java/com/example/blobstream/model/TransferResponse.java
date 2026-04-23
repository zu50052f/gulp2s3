package com.example.blobstream.model;

public record TransferResponse(
        long fileId,
        String bucket,
        String objectKey,
        int statusCode
) {
}
