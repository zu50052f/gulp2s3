package com.example.blobstream.service;

public class S3UploadException extends RuntimeException {

    public S3UploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
