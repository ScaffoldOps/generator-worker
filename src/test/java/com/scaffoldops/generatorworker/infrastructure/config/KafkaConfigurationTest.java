package com.scaffoldops.generatorworker.infrastructure.config;

import com.scaffoldops.generatorworker.domain.event.ArtifactCleanupRequestedEvent;
import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;
import com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigurationTest {

    private final KafkaConfiguration kafkaConfiguration = new KafkaConfiguration();

    @Test
    void shouldBuildConsumerFactoryWithExplicitJsonDeserializationSettings() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(java.util.List.of("kafka.scaffoldops-dev.svc.cluster.local:9092"));
        kafkaProperties.getConsumer().setGroupId("generator-worker");
        kafkaProperties.getConsumer().setAutoOffsetReset("earliest");

        ConsumerFactory<String, GenerationRequestedEvent> consumerFactory =
                kafkaConfiguration.generationRequestedEventConsumerFactory(kafkaProperties);

        Map<String, Object> properties = consumerFactory.getConfigurationProperties();

        assertThat(properties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .asList()
                .contains("kafka.scaffoldops-dev.svc.cluster.local:9092");
        assertThat(properties.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("generator-worker");
        assertThat(properties.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(properties.get(JsonDeserializer.TRUSTED_PACKAGES))
                .isEqualTo("com.scaffoldops.generatorworker.domain.event");
        assertThat(properties.get(JsonDeserializer.VALUE_DEFAULT_TYPE))
                .isEqualTo(GenerationRequestedEvent.class.getName());
        assertThat(properties.get(JsonDeserializer.USE_TYPE_INFO_HEADERS)).isEqualTo(false);
    }

    @Test
    void shouldCreateCommonErrorHandlerForGenerationConsumer() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        ProducerFactory<String, Object> producerFactory =
                kafkaConfiguration.generationRequestedDeadLetterProducerFactory(kafkaProperties);
        KafkaTemplate<String, Object> kafkaTemplate =
                kafkaConfiguration.generationRequestedDeadLetterKafkaTemplate(producerFactory);
        DeadLetterPublishingRecoverer recoverer =
                kafkaConfiguration.generationRequestedDeadLetterPublishingRecoverer(
                        kafkaTemplate,
                        kafkaTopicProperties()
                );
        CommonErrorHandler errorHandler = kafkaConfiguration.generationRequestedKafkaErrorHandler(recoverer);

        assertThat(errorHandler).isNotNull();
    }

    @Test
    void shouldCreateDeadLetterRecovererForGenerationRequestedTopic() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        ProducerFactory<String, Object> producerFactory =
                kafkaConfiguration.generationRequestedDeadLetterProducerFactory(kafkaProperties);
        KafkaTemplate<String, Object> kafkaTemplate =
                kafkaConfiguration.generationRequestedDeadLetterKafkaTemplate(producerFactory);
        DeadLetterPublishingRecoverer recoverer = kafkaConfiguration.generationRequestedDeadLetterPublishingRecoverer(
                kafkaTemplate,
                kafkaTopicProperties()
        );

        assertThat(recoverer).isNotNull();
    }

    @Test
    void shouldBuildProducerFactoryWithExplicitJsonSerializationSettings() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(java.util.List.of("kafka.scaffoldops-dev.svc.cluster.local:9092"));

        ProducerFactory<String, DeploymentRequestedEvent> producerFactory =
                kafkaConfiguration.deploymentRequestedEventProducerFactory(kafkaProperties);

        Map<String, Object> properties = producerFactory.getConfigurationProperties();

        assertThat(properties.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .asList()
                .contains("kafka.scaffoldops-dev.svc.cluster.local:9092");
        assertThat(properties.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
        assertThat(properties.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
                .isEqualTo(JsonSerializer.class);
        assertThat(properties.get(JsonSerializer.ADD_TYPE_INFO_HEADERS)).isEqualTo(false);
    }

    @Test
    void shouldBuildArtifactCleanupConsumerFactoryWithExplicitJsonDeserializationSettings() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(java.util.List.of("kafka.scaffoldops-dev.svc.cluster.local:9092"));
        kafkaProperties.getConsumer().setGroupId("generator-worker");

        ConsumerFactory<String, ArtifactCleanupRequestedEvent> consumerFactory =
                kafkaConfiguration.artifactCleanupRequestedEventConsumerFactory(kafkaProperties);

        Map<String, Object> properties = consumerFactory.getConfigurationProperties();

        assertThat(properties.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG))
                .asList()
                .contains("kafka.scaffoldops-dev.svc.cluster.local:9092");
        assertThat(properties.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("generator-worker");
        assertThat(properties.get(JsonDeserializer.TRUSTED_PACKAGES))
                .isEqualTo("com.scaffoldops.generatorworker.domain.event");
        assertThat(properties.get(JsonDeserializer.VALUE_DEFAULT_TYPE))
                .isEqualTo(ArtifactCleanupRequestedEvent.class.getName());
        assertThat(properties.get(JsonDeserializer.USE_TYPE_INFO_HEADERS)).isEqualTo(false);
    }

    private KafkaTopicProperties kafkaTopicProperties() {
        return new KafkaTopicProperties(
                "generation-requested",
                "deployment-requested",
                "generation-requested-dlt",
                "artifact-cleanup-requested"
        );
    }
}
