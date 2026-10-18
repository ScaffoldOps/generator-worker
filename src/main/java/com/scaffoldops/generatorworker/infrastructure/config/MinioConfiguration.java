package com.scaffoldops.generatorworker.infrastructure.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.artifact.storage-type", havingValue = "minio")
public class MinioConfiguration {
    @Bean
    MinioClient minioClient(@Value("${app.artifact.minio.endpoint:http://localhost:9000}") String endpoint,
                            @Value("${app.artifact.minio.access-key:minioadmin}") String accessKey,
                            @Value("${app.artifact.minio.secret-key:minioadmin}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
