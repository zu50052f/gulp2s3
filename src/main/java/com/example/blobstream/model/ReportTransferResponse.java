package com.example.blobstream.model;

public record ReportTransferResponse(
        String tableName,
        String format,
        long rowCount,
        long byteCount,
        String bucket,
        String objectKey,
        int statusCode
) {
}
