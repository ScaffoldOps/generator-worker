package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GenerationRequestedKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(GenerationRequestedKafkaListener.class);

    private final ProcessGenerationRequestUseCase processGenerationRequestUseCase;
    private final KafkaTopicProperties kafkaTopicProperties;

    public GenerationRequestedKafkaListener(
            ProcessGenerationRequestUseCase processGenerationRequestUseCase,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.processGenerationRequestUseCase = processGenerationRequestUseCase;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @KafkaListener(
            id = "generation-requested-consumer",
            topics = "${app.kafka.topics.generation-requested:generation-requested}",
            groupId = "${spring.kafka.consumer.group-id:generator-worker}",
            containerFactory = "generationRequestedKafkaListenerContainerFactory"
    )
    public void onMessage(GenerationRequestedEvent event) {
        validate(event);

        log.info(
                "Received generation-requested event topic={} requestId={} serviceName={} template={} deploymentTarget={} workerService=generator-worker",
                kafkaTopicProperties.generationRequested(),
                event.requestId(),
                event.name(),
                event.template(),
                event.deploymentTarget()
        );

        processGenerationRequestUseCase.process(new ProcessGenerationRequestUseCase.Command(
                event.requestId(),
                event.name(),
                event.template(),
                event.database(),
                event.restApi(),
                event.security(),
                event.messaging(),
                event.deploymentTarget(),
                event.status(),
                event.createdAt()
        ));
    }

    private void validate(GenerationRequestedEvent event) {
        require(event.requestId() != null, "requestId");
        require(StringUtils.hasText(event.name()), "name");
        require(StringUtils.hasText(event.template()), "template");
        require(event.database() != null, "database");
        require(event.restApi() != null, "restApi");
        require(event.security() != null, "security");
        require(event.messaging() != null, "messaging");
        require(StringUtils.hasText(event.deploymentTarget()), "deploymentTarget");
        require(StringUtils.hasText(event.status()), "status");
        require(event.createdAt() != null, "createdAt");
    }

    private void require(boolean valid, String fieldName) {
        if (!valid) {
            throw new IllegalArgumentException("generation-requested event missing required field: " + fieldName);
        }
    }
}
