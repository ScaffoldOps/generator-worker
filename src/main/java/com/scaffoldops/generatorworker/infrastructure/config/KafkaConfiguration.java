package com.scaffoldops.generatorworker.infrastructure.config;

import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;
import com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
public class KafkaConfiguration {

    @Bean
    ConsumerFactory<String, GenerationRequestedEvent> generationRequestedEventConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        properties.put(JsonDeserializer.TRUSTED_PACKAGES, "com.scaffoldops.generatorworker.domain.event");
        properties.put(JsonDeserializer.VALUE_DEFAULT_TYPE, GenerationRequestedEvent.class.getName());
        properties.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(properties);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, GenerationRequestedEvent> generationRequestedKafkaListenerContainerFactory(
            ConsumerFactory<String, GenerationRequestedEvent> generationRequestedEventConsumerFactory,
            KafkaProperties kafkaProperties,
            CommonErrorHandler generationRequestedKafkaErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, GenerationRequestedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(generationRequestedEventConsumerFactory);
        factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
        factory.setCommonErrorHandler(generationRequestedKafkaErrorHandler);
        return factory;
    }

    @Bean
    CommonErrorHandler generationRequestedKafkaErrorHandler(
            DeadLetterPublishingRecoverer generationRequestedDeadLetterPublishingRecoverer
    ) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                generationRequestedDeadLetterPublishingRecoverer,
                new FixedBackOff(1000L, 2L)
        );
        errorHandler.setAckAfterHandle(false);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    @Bean
    ProducerFactory<String, Object> generationRequestedDeadLetterProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        properties.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, Object> generationRequestedDeadLetterKafkaTemplate(
            ProducerFactory<String, Object> generationRequestedDeadLetterProducerFactory
    ) {
        return new KafkaTemplate<>(generationRequestedDeadLetterProducerFactory);
    }

    @Bean
    DeadLetterPublishingRecoverer generationRequestedDeadLetterPublishingRecoverer(
            KafkaTemplate<String, Object> generationRequestedDeadLetterKafkaTemplate,
            KafkaTopicProperties kafkaTopicProperties
    ) {
        return new DeadLetterPublishingRecoverer(
                generationRequestedDeadLetterKafkaTemplate,
                (record, exception) -> new org.apache.kafka.common.TopicPartition(
                        kafkaTopicProperties.generationRequestedDlt(),
                        record.partition()
                )
        );
    }

    @Bean
    ProducerFactory<String, DeploymentRequestedEvent> deploymentRequestedEventProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildProducerProperties());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        properties.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(properties);
    }

    @Bean
    KafkaTemplate<String, DeploymentRequestedEvent> deploymentRequestedKafkaTemplate(
            ProducerFactory<String, DeploymentRequestedEvent> deploymentRequestedEventProducerFactory
    ) {
        return new KafkaTemplate<>(deploymentRequestedEventProducerFactory);
    }
}
