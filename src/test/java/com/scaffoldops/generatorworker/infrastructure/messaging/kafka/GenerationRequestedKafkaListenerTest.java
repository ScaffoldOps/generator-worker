package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationRequestedKafkaListenerTest {

    @Test
    void shouldValidateAndDelegateToApplicationService() {
        RecordingUseCase useCase = new RecordingUseCase();
        GenerationRequestedKafkaListener listener =
                new GenerationRequestedKafkaListener(useCase, kafkaTopicProperties());

        GenerationRequestedEvent event = validEvent();

        listener.onMessage(event);

        assertThat(useCase.command).isNotNull();
        assertThat(useCase.command.requestId()).isEqualTo(event.requestId());
        assertThat(useCase.command.name()).isEqualTo(event.name());
        assertThat(useCase.command.template()).isEqualTo(event.template());
        assertThat(useCase.command.database()).isEqualTo(event.database());
        assertThat(useCase.command.restApi()).isEqualTo(event.restApi());
        assertThat(useCase.command.security()).isEqualTo(event.security());
        assertThat(useCase.command.messaging()).isEqualTo(event.messaging());
        assertThat(useCase.command.deploymentTarget()).isEqualTo(event.deploymentTarget());
        assertThat(useCase.command.status()).isEqualTo(event.generationStatus());
        assertThat(useCase.command.createdAt()).isEqualTo(event.createdAt());
    }

    @Test
    void shouldRejectEventWhenRequiredFieldIsMissing() {
        RecordingUseCase useCase = new RecordingUseCase();
        GenerationRequestedKafkaListener listener =
                new GenerationRequestedKafkaListener(useCase, kafkaTopicProperties());

        GenerationRequestedEvent event = new GenerationRequestedEvent(
                null,
                "billing-service",
                "spring-boot-hexagonal",
                true,
                true,
                true,
                false,
                "kubernetes",
                "RECEIVED",
                OffsetDateTime.now()
        );

        assertThatThrownBy(() -> listener.onMessage(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("generation-requested event missing required field: requestId");

        assertThat(useCase.command).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"status", "generationStatus"})
    void shouldDeserializeAndProcessApiPayload(String statusField) throws Exception {
        RecordingUseCase useCase = new RecordingUseCase();
        GenerationRequestedKafkaListener listener =
                new GenerationRequestedKafkaListener(useCase, kafkaTopicProperties());
        try (var fixture = getClass().getResourceAsStream("/contracts/generation-requested.json");
             var deserializer = new org.springframework.kafka.support.serializer.JsonDeserializer<>(
                     GenerationRequestedEvent.class, false)) {
            String payload = new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"generationStatus\"", "\"" + statusField + "\"");
            GenerationRequestedEvent event = deserializer.deserialize("generation-requested",
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            listener.onMessage(event);
            assertThat(useCase.command).isNotNull();
            assertThat(useCase.command.status()).isEqualTo("RECEIVED");
            assertThat(useCase.command.requestId()).isEqualTo(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"));
            assertThat(useCase.command.deploymentTarget()).isEqualTo("KUBERNETES");
            assertThat(useCase.command.createdAt()).isEqualTo(
                    OffsetDateTime.parse("2026-03-07T10:15:30Z"));
        }
    }

    @Test
    void shouldRejectPayloadWithoutEitherStatusField() throws Exception {
        RecordingUseCase useCase = new RecordingUseCase();
        var listener = new GenerationRequestedKafkaListener(useCase, kafkaTopicProperties());
        var mapper = org.springframework.kafka.support.JacksonUtils.enhancedObjectMapper();
        try (var fixture = getClass().getResourceAsStream("/contracts/generation-requested.json")) {
            var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fixture);
            payload.remove("generationStatus");
            var event = mapper.treeToValue(payload, GenerationRequestedEvent.class);
            assertThatThrownBy(() -> listener.onMessage(event))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("generation-requested event missing required field: generationStatus");
            assertThat(useCase.command).isNull();
        }
    }

    private GenerationRequestedEvent validEvent() {
        return new GenerationRequestedEvent(
                UUID.randomUUID(),
                "billing-service",
                "spring-boot-hexagonal",
                true,
                true,
                true,
                false,
                "kubernetes",
                "RECEIVED",
                OffsetDateTime.parse("2026-03-24T12:00:00Z")
        );
    }

    private KafkaTopicProperties kafkaTopicProperties() {
        return new KafkaTopicProperties(
                "generation-requested",
                "deployment-requested",
                "generation-requested-dlt",
                "artifact-cleanup-requested"
        );
    }

    private static final class RecordingUseCase implements ProcessGenerationRequestUseCase {
        private Command command;

        @Override
        public void process(Command command) {
            this.command = command;
        }
    }
}
