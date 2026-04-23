package com.example.blobstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BlobStreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlobStreamApplication.class, args);
    }
}
