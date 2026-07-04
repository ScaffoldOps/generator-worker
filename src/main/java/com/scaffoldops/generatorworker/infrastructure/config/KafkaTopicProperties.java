package com.scaffoldops.generatorworker.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicProperties(
        String generationRequested,
        String deploymentRequested,
        String generationRequestedDlt,
        String artifactCleanupRequested
) {
}
