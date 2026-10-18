package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.application.port.out.DeploymentRequestedPublisherPort;
import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

@Component
public class DeploymentRequestedKafkaPublisher implements DeploymentRequestedPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRequestedKafkaPublisher.class);

    private final KafkaTemplate<String, DeploymentRequestedEvent> kafkaTemplate;
    private final KafkaTopicProperties kafkaTopicProperties;
    private final Path handoffStateDirectory;

    public DeploymentRequestedKafkaPublisher(
            KafkaTemplate<String, DeploymentRequestedEvent> kafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties,
            @Value("${app.generation.handoff-state-dir:${java.io.tmpdir}/generator-worker/handoff}")
            String handoffStateDirectory
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.kafkaTopicProperties = kafkaTopicProperties;
        this.handoffStateDirectory = Path.of(handoffStateDirectory);
    }

    @Override
    public void publish(DeploymentRequestedEvent event) {
        try {
            Files.createDirectories(handoffStateDirectory);
            Path publicationMarker = handoffStateDirectory.resolve("deployment-requested-" + event.requestId() + ".marker");
            if (Files.exists(publicationMarker)) {
                log.info(
                        "Skipping deployment-requested publish because marker exists topic={} requestId={} workerService=generator-worker",
                        kafkaTopicProperties.deploymentRequested(),
                        event.requestId()
                );
                return;
            }

            kafkaTemplate.send(kafkaTopicProperties.deploymentRequested(), event.requestId().toString(), event).get();
            Files.writeString(
                    publicationMarker,
                    "requestId=" + event.requestId() + System.lineSeparator()
                            + "artifactReference=" + event.artifactReference() + System.lineSeparator()
                            + "createdAt=" + event.createdAt()
            );
            log.info(
                    "Published deployment-requested event topic={} requestId={} artifactReference={} workerService=generator-worker",
                    kafkaTopicProperties.deploymentRequested(),
                    event.requestId(),
                    event.artifactReference()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist deployment-requested handoff marker for requestId=" + event.requestId(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("deployment-requested publication interrupted for requestId=" + event.requestId(), exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("deployment-requested publication failed for requestId=" + event.requestId(), exception);
        }
    }
}
