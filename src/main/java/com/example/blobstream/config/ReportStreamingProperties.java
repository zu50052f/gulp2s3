package com.example.blobstream.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashSet;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "app.report")
public class ReportStreamingProperties {

    @Min(1)
    private int fetchSize = 1_000;

    @Min(1_024)
    private int pipeBufferBytes = 65_536;

    @Min(5 * 1024 * 1024)
    private int multipartPartSizeBytes = 8 * 1024 * 1024;

    @NotEmpty
    private Set<String> allowedTables = new LinkedHashSet<>();

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public int getPipeBufferBytes() {
        return pipeBufferBytes;
    }

    public void setPipeBufferBytes(int pipeBufferBytes) {
        this.pipeBufferBytes = pipeBufferBytes;
    }

    public int getMultipartPartSizeBytes() {
        return multipartPartSizeBytes;
    }

    public void setMultipartPartSizeBytes(int multipartPartSizeBytes) {
        this.multipartPartSizeBytes = multipartPartSizeBytes;
    }

    public Set<String> getAllowedTables() {
        return allowedTables;
    }

    public void setAllowedTables(Set<String> allowedTables) {
        this.allowedTables = allowedTables;
    }
}
