package com.example.blobstream.controller;

import com.example.blobstream.model.ReportTransferResponse;
import com.example.blobstream.service.ReportStreamingService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportTransferController {

    private final ReportStreamingService reportStreamingService;

    public ReportTransferController(ReportStreamingService reportStreamingService) {
        this.reportStreamingService = reportStreamingService;
    }

    @PostMapping("/{tableName}/transfer")
    public ReportTransferResponse streamTableToS3(
            @PathVariable String tableName,
            @RequestParam(name = "objectKey", required = false) String objectKey,
            @RequestParam(name = "zip", defaultValue = "false") boolean zip
    ) {
        return reportStreamingService.streamTableAsCsvToS3(tableName, objectKey, zip);
    }
}
