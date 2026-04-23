package com.example.blobstream.web;

import com.example.blobstream.service.S3HttpUploader;
import com.example.blobstream.service.S3UploadException;
import com.example.blobstream.service.DatabaseStorageException;
import com.example.blobstream.service.ReportStreamingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class StorageExceptionHandler {

    @ExceptionHandler(S3HttpUploader.S3RequestException.class)
    public ResponseEntity<Map<String, Object>> handleS3RequestException(S3HttpUploader.S3RequestException ex) {
        HttpStatus status = ex.statusCode() == 507
                ? HttpStatus.INSUFFICIENT_STORAGE
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of(
                "error", "storage_request_failed",
                "upstreamStatus", ex.statusCode(),
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(S3UploadException.class)
    public ResponseEntity<Map<String, Object>> handleS3UploadException(S3UploadException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "error", "storage_upload_failed",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler({DatabaseStorageException.class, ReportStreamingException.class})
    public ResponseEntity<Map<String, Object>> handleStoragePipelineFailure(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "storage_pipeline_failed",
                "message", ex.getMessage()
        ));
    }
}
