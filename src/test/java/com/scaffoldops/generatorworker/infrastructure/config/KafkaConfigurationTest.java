package com.scaffoldops.generatorworker.infrastructure.config;

import com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

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
}
