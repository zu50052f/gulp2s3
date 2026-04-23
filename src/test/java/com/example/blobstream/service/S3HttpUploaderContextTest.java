package com.example.blobstream.service;

import com.example.blobstream.config.S3StorageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class S3HttpUploaderContextTest {

    @Test
    void createsComponentBeanUsingInjectedConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(S3StorageProperties.class, () -> {
                S3StorageProperties properties = new S3StorageProperties();
                properties.setEndpoint(URI.create("http://localhost:9000"));
                properties.setRegion("us-east-1");
                properties.setBucket("blob-files");
                properties.setAccessKey("minioadmin");
                properties.setSecretKey("minioadmin");
                return properties;
            });
            context.registerBean(ObservationRegistry.class, ObservationRegistry::create);
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(S3HttpUploader.class);

            context.refresh();

            assertThat(context.getBean(S3HttpUploader.class)).isNotNull();
        }
    }
}
