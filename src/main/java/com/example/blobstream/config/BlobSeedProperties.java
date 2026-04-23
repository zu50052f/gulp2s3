package com.example.blobstream.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.seed-blob")
public class BlobSeedProperties {

    private boolean enabled;

    private boolean exitAfterSeed;

    @NotBlank
    private String fileName = "seed-file.bin";

    @NotBlank
    private String contentType = "application/octet-stream";

    @NotBlank
    private String objectKey = "blob/load-test/seed-file.bin";

    @Min(1)
    private long sizeBytes = 4L * 1024 * 1024 * 1024;

    @Min(1_024)
    private int chunkSizeBytes = 8 * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExitAfterSeed() {
        return exitAfterSeed;
    }

    public void setExitAfterSeed(boolean exitAfterSeed) {
        this.exitAfterSeed = exitAfterSeed;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public void setChunkSizeBytes(int chunkSizeBytes) {
        this.chunkSizeBytes = chunkSizeBytes;
    }
}
