package com.example.blobstream.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.s3")
public class S3StorageProperties {

    @NotNull
    private URI endpoint;

    @NotBlank
    private String region;

    @NotBlank
    private String bucket;

    @NotBlank
    private String accessKey;

    @NotBlank
    private String secretKey;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration readTimeout = Duration.ofMinutes(10);

    @Min(5 * 1024 * 1024)
    private int multipartPartSizeBytes = 8 * 1024 * 1024;

    @Min(1)
    private int multipartRetryMaxAttempts = 4;

    @NotNull
    private Duration multipartRetryBaseDelay = Duration.ofSeconds(1);

    private boolean deleteExistingObjectBeforeWrite;

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMultipartPartSizeBytes() {
        return multipartPartSizeBytes;
    }

    public void setMultipartPartSizeBytes(int multipartPartSizeBytes) {
        this.multipartPartSizeBytes = multipartPartSizeBytes;
    }

    public int getMultipartRetryMaxAttempts() {
        return multipartRetryMaxAttempts;
    }

    public void setMultipartRetryMaxAttempts(int multipartRetryMaxAttempts) {
        this.multipartRetryMaxAttempts = multipartRetryMaxAttempts;
    }

    public Duration getMultipartRetryBaseDelay() {
        return multipartRetryBaseDelay;
    }

    public void setMultipartRetryBaseDelay(Duration multipartRetryBaseDelay) {
        this.multipartRetryBaseDelay = multipartRetryBaseDelay;
    }

    public boolean isDeleteExistingObjectBeforeWrite() {
        return deleteExistingObjectBeforeWrite;
    }

    public void setDeleteExistingObjectBeforeWrite(boolean deleteExistingObjectBeforeWrite) {
        this.deleteExistingObjectBeforeWrite = deleteExistingObjectBeforeWrite;
    }
}
