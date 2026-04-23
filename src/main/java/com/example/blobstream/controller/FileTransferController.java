package com.example.blobstream.controller;

import com.example.blobstream.model.StoredFileResponse;
import com.example.blobstream.model.TransferResponse;
import com.example.blobstream.service.DatabaseFileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/files")
public class FileTransferController {

    private final DatabaseFileService databaseFileService;

    public FileTransferController(DatabaseFileService databaseFileService) {
        this.databaseFileService = databaseFileService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFileResponse> uploadToDatabase(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "objectKey", required = false) String objectKey
    ) {
        StoredFileResponse response = StoredFileResponse.from(databaseFileService.store(file, objectKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public List<StoredFileResponse> listFiles() {
        return databaseFileService.listFiles().stream()
                .map(StoredFileResponse::from)
                .toList();
    }

    @PostMapping("/{id}/transfer")
    public TransferResponse transferToS3(@PathVariable long id) {
        return databaseFileService.transferToS3(id);
    }
}
