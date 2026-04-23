package com.example.blobstream.web;

import com.example.blobstream.service.S3HttpUploader;
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
}
