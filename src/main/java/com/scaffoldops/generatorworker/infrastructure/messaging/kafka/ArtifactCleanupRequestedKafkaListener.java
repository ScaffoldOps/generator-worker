package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.application.port.in.CleanupGeneratedArtifactUseCase;
import com.scaffoldops.generatorworker.domain.event.ArtifactCleanupRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ArtifactCleanupRequestedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCleanupRequestedKafkaListener.class);

    private final CleanupGeneratedArtifactUseCase cleanupGeneratedArtifactUseCase;
    private final KafkaTopicProperties kafkaTopicProperties;

    public ArtifactCleanupRequestedKafkaListener(
            CleanupGeneratedArtifactUseCase cleanupGeneratedArtifactUseCase,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.cleanupGeneratedArtifactUseCase = cleanupGeneratedArtifactUseCase;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @KafkaListener(
            id = "artifact-cleanup-requested-consumer",
            topics = "${app.kafka.topics.artifact-cleanup-requested:artifact-cleanup-requested}",
            groupId = "${spring.kafka.consumer.group-id:generator-worker}",
            containerFactory = "artifactCleanupRequestedKafkaListenerContainerFactory"
    )
    public void onMessage(ArtifactCleanupRequestedEvent event) {
        validate(event);

        log.info(
                "Received artifact-cleanup-requested event topic={} requestId={} serviceName={} workerService=generator-worker",
                kafkaTopicProperties.artifactCleanupRequested(),
                event.requestId(),
                event.name()
        );

        cleanupGeneratedArtifactUseCase.cleanup(new CleanupGeneratedArtifactUseCase.Command(
                event.requestId(),
                event.name(),
                event.deletedAt()
        ));
    }

    private void validate(ArtifactCleanupRequestedEvent event) {
        require(event.requestId() != null, "requestId");
        require(StringUtils.hasText(event.name()), "name");
        require(event.deletedAt() != null, "deletedAt");
    }

    private void require(boolean valid, String fieldName) {
        if (!valid) {
            throw new IllegalArgumentException("artifact-cleanup-requested event missing required field: " + fieldName);
        }
    }
}
