package com.example.blobstream.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class StoredFileNotFoundException extends RuntimeException {

    public StoredFileNotFoundException(long id) {
        super("Stored file " + id + " was not found.");
    }
}
