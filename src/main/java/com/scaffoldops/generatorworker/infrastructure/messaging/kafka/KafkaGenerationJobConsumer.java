package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationJobUseCase;
import com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaGenerationJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaGenerationJobConsumer.class);

    private final ProcessGenerationJobUseCase processGenerationJobUseCase;
    private final KafkaTopicProperties kafkaTopicProperties;

    public KafkaGenerationJobConsumer(
            ProcessGenerationJobUseCase processGenerationJobUseCase,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        this.processGenerationJobUseCase = processGenerationJobUseCase;
        this.kafkaTopicProperties = kafkaTopicProperties;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.generation-requested:generation-requested}",
            groupId = "${spring.kafka.consumer.group-id:generator-worker}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(GenerationRequestedEvent event) {
        log.info(
                "Received generation request event from topic={} requestId={} serviceName={}",
                kafkaTopicProperties.generationRequested(),
                event.requestId(),
                event.name()
        );

        processGenerationJobUseCase.process(new ProcessGenerationJobUseCase.Command(
                event.requestId(),
                event.name(),
                event.template(),
                event.database(),
                event.restApi(),
                event.security(),
                event.messaging(),
                event.deploymentTarget(),
                event.createdAt()
        ));
    }
}
