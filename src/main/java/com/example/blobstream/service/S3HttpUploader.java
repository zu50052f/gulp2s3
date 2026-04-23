package com.example.blobstream.service;

import com.example.blobstream.config.S3StorageProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class S3HttpUploader {

    private static final Logger log = LoggerFactory.getLogger(S3HttpUploader.class);

    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private final S3StorageProperties properties;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final DistributionSummary putBytesFixed;
    private final DistributionSummary putBytesMultipart;
    private final Counter putRequestsFixedSuccess;
    private final Counter putRequestsFixedError;
    private final Counter putRequestsMultipartSuccess;
    private final Counter putRequestsMultipartError;
    private final Timer putDurationFixedSuccess;
    private final Timer putDurationFixedError;
    private final Timer putDurationMultipartSuccess;
    private final Timer putDurationMultipartError;

    @Autowired
    public S3HttpUploader(
            S3StorageProperties properties,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry
    ) {
        this(properties, Clock.systemUTC(), observationRegistry, meterRegistry);
    }

    private S3HttpUploader(
            S3StorageProperties properties,
            Clock clock,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.clock = clock;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.putBytesFixed = DistributionSummary.builder("blobstream.s3.put.bytes")
                .baseUnit("bytes")
                .tag("mode", "fixed")
                .tag("content_type", "application/octet-stream")
                .register(meterRegistry);
        this.putBytesMultipart = DistributionSummary.builder("blobstream.s3.put.bytes")
                .baseUnit("bytes")
                .tag("mode", "multipart")
                .tag("content_type", "application/octet-stream")
                .register(meterRegistry);
        this.putRequestsFixedSuccess = Counter.builder("blobstream.s3.put.requests").tag("mode", "fixed").tag("result", "success").register(meterRegistry);
        this.putRequestsFixedError = Counter.builder("blobstream.s3.put.requests").tag("mode", "fixed").tag("result", "error").register(meterRegistry);
        this.putRequestsMultipartSuccess = Counter.builder("blobstream.s3.put.requests").tag("mode", "multipart").tag("result", "success").register(meterRegistry);
        this.putRequestsMultipartError = Counter.builder("blobstream.s3.put.requests").tag("mode", "multipart").tag("result", "error").register(meterRegistry);
        this.putDurationFixedSuccess = Timer.builder("blobstream.s3.put.duration").tag("mode", "fixed").tag("result", "success").register(meterRegistry);
        this.putDurationFixedError = Timer.builder("blobstream.s3.put.duration").tag("mode", "fixed").tag("result", "error").register(meterRegistry);
        this.putDurationMultipartSuccess = Timer.builder("blobstream.s3.put.duration").tag("mode", "multipart").tag("result", "success").register(meterRegistry);
        this.putDurationMultipartError = Timer.builder("blobstream.s3.put.duration").tag("mode", "multipart").tag("result", "error").register(meterRegistry);
    }

    public int upload(String objectKey, String contentType, long contentLength, InputStream data) {
        Observation observation = Observation.createNotStarted("blobstream.s3.put", observationRegistry)
                .contextualName("s3-put-object-request")
                .lowCardinalityKeyValue("protocol", scheme())
                .lowCardinalityKeyValue("mode", "fixed")
                .lowCardinalityKeyValue("request", "put_object")
                .lowCardinalityKeyValue("target", "s3");
        Timer.Sample timerSample = Timer.start();
        String result = "success";
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            deleteExistingObjectIfConfigured(objectKey);
            S3Response response = executeRequest(
                    "PUT",
                    objectKey,
                    Map.of(),
                    contentType,
                    contentLength,
                    data,
                    true
            );
            recordSuccessMetrics(contentLength, "fixed", contentType);
            return response.statusCode();
        } catch (RuntimeException ex) {
            result = "error";
            observation.error(ex);
            throw ex;
        } catch (IOException ex) {
            result = "error";
            observation.error(ex);
            throw new S3UploadException("S3 upload failed.", ex);
        } finally {
            observation.lowCardinalityKeyValue("result", result);
            observation.stop();
            ("success".equals(result) ? putRequestsFixedSuccess : putRequestsFixedError).increment();
            timerSample.stop("success".equals(result) ? putDurationFixedSuccess : putDurationFixedError);
        }
    }

    public int uploadMultipart(String objectKey, String contentType, InputStream data) {
        MultipartUpload multipartUpload = startMultipartUpload(objectKey, contentType);
        List<UploadedPart> uploadedParts = new ArrayList<>();
        byte[] buffer = new byte[properties.getMultipartPartSizeBytes()];
        int nextPartNumber = 1;
        boolean completed = false;

        try {
            while (true) {
                int bytesRead = data.readNBytes(buffer, 0, buffer.length);
                if (bytesRead == 0) {
                    break;
                }

                uploadedParts.add(uploadPart(
                        objectKey,
                        multipartUpload.uploadId(),
                        nextPartNumber++,
                        buffer,
                        bytesRead
                ));
            }

            if (uploadedParts.isEmpty()) {
                uploadedParts.add(uploadPart(
                        objectKey,
                        multipartUpload.uploadId(),
                        nextPartNumber,
                        new byte[0],
                        0
                ));
            }

            int statusCode = completeMultipartUpload(objectKey, multipartUpload.uploadId(), uploadedParts);
            completed = true;
            return statusCode;
        } catch (IOException ex) {
            throw new S3UploadException("S3 multipart upload failed.", ex);
        } finally {
            if (!completed) {
                try {
                    abortMultipartUpload(objectKey, multipartUpload.uploadId());
                } catch (RuntimeException ignored) {
                    // Best-effort abort to avoid orphaned multipart uploads.
                }
            }
        }
    }

    public boolean shouldUseMultipart(long contentLength) {
        return contentLength > properties.getMultipartPartSizeBytes();
    }

    public MultipartUpload startMultipartUpload(String objectKey, String contentType) {
        try {
            deleteExistingObjectIfConfigured(objectKey);
            S3Response response = executeRequest(
                    "POST",
                    objectKey,
                    Map.of("uploads", ""),
                    contentType,
                    0,
                    null,
                    false
            );
            String uploadId = requiredXmlValue(response.body(), "UploadId");
            return new MultipartUpload(objectKey, uploadId);
        } catch (IOException ex) {
            throw new S3UploadException("Failed to start S3 multipart upload.", ex);
        }
    }

    public UploadedPart uploadPart(String objectKey, String uploadId, int partNumber, byte[] bytes, int length) {
        Observation observation = Observation.createNotStarted("blobstream.s3.put", observationRegistry)
                .contextualName("s3-upload-part-request")
                .lowCardinalityKeyValue("protocol", scheme())
                .lowCardinalityKeyValue("mode", "multipart")
                .lowCardinalityKeyValue("request", "upload_part")
                .lowCardinalityKeyValue("target", "s3");
        Timer.Sample timerSample = Timer.start();
        String result = "success";
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            S3Response response = uploadPartWithRetries(objectKey, uploadId, partNumber, bytes, length);
            String eTag = response.header("ETag");
            if (eTag == null || eTag.isBlank()) {
                throw new S3UploadException("S3 multipart upload part response did not include an ETag.", new IllegalStateException("Missing ETag"));
            }
            recordSuccessMetrics(length, "multipart", "application/octet-stream");
            return new UploadedPart(partNumber, eTag, length);
        } catch (RuntimeException ex) {
            result = "error";
            observation.error(ex);
            throw ex;
        } catch (IOException ex) {
            result = "error";
            observation.error(ex);
            throw new S3UploadException("S3 multipart part upload failed.", ex);
        } finally {
            observation.lowCardinalityKeyValue("result", result);
            observation.stop();
            ("success".equals(result) ? putRequestsMultipartSuccess : putRequestsMultipartError).increment();
            timerSample.stop("success".equals(result) ? putDurationMultipartSuccess : putDurationMultipartError);
        }
    }

    private S3Response uploadPartWithRetries(String objectKey, String uploadId, int partNumber, byte[] bytes, int length) throws IOException {
        IOException lastFailure = null;
        int maxAttempts = properties.getMultipartRetryMaxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeRequest(
                        "PUT",
                        objectKey,
                        Map.of(
                                "partNumber", Integer.toString(partNumber),
                                "uploadId", uploadId
                        ),
                        "application/octet-stream",
                        length,
                        new ByteArrayInputStream(bytes, 0, length),
                        true
                );
            } catch (IOException ex) {
                lastFailure = ex;
                if (attempt >= maxAttempts) {
                    break;
                }
                Duration delay = retryDelay(attempt);
                log.warn(
                        "Retrying multipart upload for objectKey={} partNumber={} after attempt {}/{} failed: {}",
                        objectKey,
                        partNumber,
                        attempt,
                        maxAttempts,
                        ex.getMessage()
                );
                sleep(delay);
            }
        }

        throw lastFailure == null
                ? new IOException("Multipart upload part failed without an underlying IOException.")
                : lastFailure;
    }

    private Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.max(0, attempt - 1);
        return properties.getMultipartRetryBaseDelay().multipliedBy(multiplier);
    }

    private void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting to retry multipart upload.", ex);
        }
    }

    public int completeMultipartUpload(String objectKey, String uploadId, List<UploadedPart> parts) {
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("At least one uploaded part is required to complete an S3 multipart upload.");
        }

        byte[] requestBody = completeMultipartRequest(parts).getBytes(StandardCharsets.UTF_8);
        try {
            S3Response response = executeRequest(
                    "POST",
                    objectKey,
                    Map.of("uploadId", uploadId),
                    "application/xml",
                    requestBody.length,
                    new ByteArrayInputStream(requestBody),
                    true
            );
            return response.statusCode();
        } catch (IOException ex) {
            throw new S3UploadException("Failed to complete S3 multipart upload.", ex);
        }
    }

    public void abortMultipartUpload(String objectKey, String uploadId) {
        try {
            executeRequest(
                    "DELETE",
                    objectKey,
                    Map.of("uploadId", uploadId),
                    null,
                    0,
                    null,
                    false
            );
        } catch (IOException ex) {
            throw new S3UploadException("Failed to abort S3 multipart upload.", ex);
        }
    }

    public String bucket() {
        return properties.getBucket();
    }

    private void deleteExistingObjectIfConfigured(String objectKey) throws IOException {
        if (!properties.isDeleteExistingObjectBeforeWrite()) {
            return;
        }
        executeRequest(
                "DELETE",
                objectKey,
                Map.of(),
                null,
                0,
                null,
                false
        );
    }

    private S3Response executeRequest(
            String method,
            String objectKey,
            Map<String, String> queryParameters,
            String contentType,
            long contentLength,
            InputStream requestBody,
            boolean hasRequestBody
    ) throws IOException {
        URI endpoint = properties.getEndpoint();
        String canonicalUri = canonicalUri(properties.getBucket(), objectKey);
        String canonicalQuery = canonicalQueryString(queryParameters);
        String target = canonicalQuery.isBlank() ? canonicalUri : canonicalUri + "?" + canonicalQuery;

        URL url = endpoint.resolve(target).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        connection.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));
        connection.setDoOutput(hasRequestBody);
        if (hasRequestBody) {
            connection.setFixedLengthStreamingMode(contentLength);
        }

        Instant now = clock.instant();
        String amzDate = AMZ_DATE_FORMAT.format(now);
        String dateStamp = DATE_STAMP_FORMAT.format(now);
        String hostHeader = hostHeader(endpoint);

        Map<String, String> headersToSign = new TreeMap<>();
        headersToSign.put("host", hostHeader);
        headersToSign.put("x-amz-content-sha256", UNSIGNED_PAYLOAD);
        headersToSign.put("x-amz-date", amzDate);

        if (contentType != null && !contentType.isBlank()) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Host", hostHeader);
        connection.setRequestProperty("x-amz-content-sha256", UNSIGNED_PAYLOAD);
        connection.setRequestProperty("x-amz-date", amzDate);
        connection.setRequestProperty("Authorization", authorizationHeader(method, canonicalUri, canonicalQuery, headersToSign, amzDate, dateStamp));

        if (hasRequestBody) {
            try (OutputStream outputStream = connection.getOutputStream()) {
                if (requestBody != null) {
                    requestBody.transferTo(outputStream);
                }
            } catch (IOException ex) {
                throw enrichWriteFailure(connection, method, target, ex);
            }
        }

        int statusCode = connection.getResponseCode();
        String responseBody = readBody(statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>(connection.getHeaderFields());
        connection.disconnect();

        if (statusCode >= 200 && statusCode < 300) {
            return new S3Response(statusCode, responseBody, responseHeaders);
        }

        throw new S3RequestException(statusCode, method, target, responseBody);
    }

    private IOException enrichWriteFailure(HttpURLConnection connection, String method, String target, IOException cause) {
        try {
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection.getErrorStream());
            return new IOException(
                    "Failed while streaming request body for " + method + " " + target
                            + "; server responded with status " + statusCode
                            + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody),
                    cause
            );
        } catch (IOException ignored) {
            return new IOException("Failed while streaming request body for " + method + " " + target + ".", cause);
        } finally {
            connection.disconnect();
        }
    }

    private void recordSuccessMetrics(long bytesTransferred, String mode, String contentType) {
        if ("multipart".equals(mode)) {
            putBytesMultipart.record(bytesTransferred);
            return;
        }
        if ("application/octet-stream".equals(normalizeContentType(contentType))) {
            putBytesFixed.record(bytesTransferred);
            return;
        }
        DistributionSummary.builder("blobstream.s3.put.bytes")
                .baseUnit("bytes")
                .tag("mode", mode)
                .tag("content_type", normalizeContentType(contentType))
                .register(meterRegistry)
                .record(bytesTransferred);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "unknown";
        }
        int separator = contentType.indexOf(';');
        if (separator > -1) {
            return contentType.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        }
        return contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String scheme() {
        return properties.getEndpoint().getScheme() == null ? "http" : properties.getEndpoint().getScheme().toLowerCase(Locale.ROOT);
    }

    private String authorizationHeader(
            String method,
            String canonicalUri,
            String canonicalQuery,
            Map<String, String> headersToSign,
            String amzDate,
            String dateStamp
    ) {
        String canonicalHeaders = headersToSign.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n")
                .reduce("", String::concat);
        String signedHeaders = String.join(";", headersToSign.keySet());

        String canonicalRequest = String.join(
                "\n",
                List.of(
                        method,
                        canonicalUri,
                        canonicalQuery,
                        canonicalHeaders,
                        signedHeaders,
                        UNSIGNED_PAYLOAD
                )
        );

        String credentialScope = dateStamp + "/" + properties.getRegion() + "/s3/aws4_request";
        String stringToSign = String.join(
                "\n",
                List.of(
                        "AWS4-HMAC-SHA256",
                        amzDate,
                        credentialScope,
                        sha256Hex(canonicalRequest)
                )
        );

        String signature = HexFormat.of().formatHex(signingKey(dateStamp, stringToSign));
        return "AWS4-HMAC-SHA256 Credential="
                + properties.getAccessKey()
                + "/"
                + credentialScope
                + ", SignedHeaders="
                + signedHeaders
                + ", Signature="
                + signature;
    }

    private byte[] signingKey(String dateStamp, String stringToSign) {
        byte[] kSecret = ("AWS4" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmac(kSecret, dateStamp);
        byte[] kRegion = hmac(kDate, properties.getRegion());
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        return hmac(kSigning, stringToSign);
    }

    private byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256.", ex);
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private String canonicalUri(String bucket, String objectKey) {
        StringBuilder builder = new StringBuilder();
        builder.append('/').append(encodePathSegment(bucket));
        for (String segment : objectKey.split("/")) {
            builder.append('/').append(encodePathSegment(segment));
        }
        return builder.toString();
    }

    private String canonicalQueryString(Map<String, String> queryParameters) {
        if (queryParameters.isEmpty()) {
            return "";
        }
        return queryParameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encodeQueryComponent(entry.getKey()) + "=" + encodeQueryComponent(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String encodePathSegment(String rawValue) {
        return encodeValue(rawValue, true);
    }

    private String encodeQueryComponent(String rawValue) {
        return encodeValue(rawValue, false);
    }

    private String encodeValue(String rawValue, boolean allowSlash) {
        byte[] bytes = rawValue.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder();
        for (byte currentByte : bytes) {
            int unsigned = currentByte & 0xff;
            if (isUnreserved(unsigned) || (allowSlash && unsigned == '/')) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private String requiredXmlValue(String xml, String tagName) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            var nodes = document.getElementsByTagName(tagName);
            if (nodes.getLength() == 0) {
                throw new S3UploadException("S3 response did not contain the expected <" + tagName + "> value.", new IllegalStateException("Missing XML tag"));
            }
            String value = nodes.item(0).getTextContent();
            if (value == null || value.isBlank()) {
                throw new S3UploadException("S3 response contained an empty <" + tagName + "> value.", new IllegalStateException("Empty XML tag"));
            }
            return value;
        } catch (S3UploadException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new S3UploadException("Failed to parse S3 XML response.", ex);
        }
    }

    private String completeMultipartRequest(List<UploadedPart> parts) {
        List<UploadedPart> sortedParts = new ArrayList<>(parts);
        sortedParts.sort((left, right) -> Integer.compare(left.partNumber(), right.partNumber()));

        StringBuilder xml = new StringBuilder();
        xml.append("<CompleteMultipartUpload>");
        for (UploadedPart part : sortedParts) {
            xml.append("<Part>");
            xml.append("<PartNumber>").append(part.partNumber()).append("</PartNumber>");
            xml.append("<ETag>").append(escapeXml(part.eTag())).append("</ETag>");
            xml.append("</Part>");
        }
        xml.append("</CompleteMultipartUpload>");
        return xml.toString();
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '_'
                || value == '.'
                || value == '~';
    }

    private String hostHeader(URI endpoint) {
        int port = endpoint.getPort();
        int defaultPort = "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80;
        if (port == -1 || port == defaultPort) {
            return endpoint.getHost().toLowerCase(Locale.ROOT);
        }
        return endpoint.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private void drain(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return;
        }
        try (inputStream) {
            inputStream.transferTo(OutputStream.nullOutputStream());
        }
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record MultipartUpload(String objectKey, String uploadId) {
    }

    public record UploadedPart(int partNumber, String eTag, long size) {
    }

    public static final class S3RequestException extends IllegalStateException {
        private final int statusCode;

        private S3RequestException(int statusCode, String method, String target, String responseBody) {
            super(
                    "S3 request failed with status " + statusCode
                            + " for " + method + " " + target
                            + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody)
            );
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    private record S3Response(int statusCode, String body, Map<String, List<String>> headers) {
        private String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .filter(values -> values != null && !values.isEmpty())
                    .map(values -> values.getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }
}
