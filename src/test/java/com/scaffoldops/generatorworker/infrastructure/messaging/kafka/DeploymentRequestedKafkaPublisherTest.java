package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentRequestedKafkaPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistMarkerAndSkipDuplicatePublication() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, DeploymentRequestedEvent> kafkaTemplate = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, DeploymentRequestedEvent>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq("deployment-requested"), any(String.class), any(DeploymentRequestedEvent.class))).thenReturn(future);

        DeploymentRequestedKafkaPublisher publisher = new DeploymentRequestedKafkaPublisher(
                kafkaTemplate,
                new KafkaTopicProperties(
                        "generation-requested",
                        "deployment-requested",
                        "generation-requested-dlt",
                        "artifact-cleanup-requested"
                ),
                tempDir.toString()
        );

        DeploymentRequestedEvent event = new DeploymentRequestedEvent(
                UUID.randomUUID(),
                "billing-service",
                "kubernetes",
                "file:///tmp/manifest.json",
                OffsetDateTime.parse("2026-04-08T16:00:00Z")
        );

        publisher.publish(event);
        publisher.publish(event);

        verify(kafkaTemplate, times(1)).send(eq("deployment-requested"), eq(event.requestId().toString()), eq(event));
        Path markerPath = tempDir.resolve("deployment-requested-" + event.requestId() + ".marker");
        assertThat(Files.exists(markerPath)).isTrue();
        assertThat(Files.readString(markerPath)).contains(event.artifactReference());
    }
}
