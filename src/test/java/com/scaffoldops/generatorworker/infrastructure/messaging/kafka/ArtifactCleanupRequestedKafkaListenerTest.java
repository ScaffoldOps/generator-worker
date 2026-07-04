package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;

import com.scaffoldops.generatorworker.application.port.in.CleanupGeneratedArtifactUseCase;
import com.scaffoldops.generatorworker.domain.event.ArtifactCleanupRequestedEvent;
import com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactCleanupRequestedKafkaListenerTest {

    @Test
    void shouldValidateAndDelegateToApplicationService() {
        RecordingUseCase useCase = new RecordingUseCase();
        ArtifactCleanupRequestedKafkaListener listener = new ArtifactCleanupRequestedKafkaListener(
                useCase,
                kafkaTopicProperties()
        );
        ArtifactCleanupRequestedEvent event = validEvent();

        listener.onMessage(event);

        assertThat(useCase.command).isNotNull();
        assertThat(useCase.command.requestId()).isEqualTo(event.requestId());
        assertThat(useCase.command.name()).isEqualTo(event.name());
        assertThat(useCase.command.deletedAt()).isEqualTo(event.deletedAt());
    }

    @Test
    void shouldRejectEventWhenRequiredFieldIsMissing() {
        RecordingUseCase useCase = new RecordingUseCase();
        ArtifactCleanupRequestedKafkaListener listener = new ArtifactCleanupRequestedKafkaListener(
                useCase,
                kafkaTopicProperties()
        );
        ArtifactCleanupRequestedEvent event = new ArtifactCleanupRequestedEvent(
                null,
                "billing-service",
                OffsetDateTime.parse("2026-03-07T10:15:30Z")
        );

        assertThatThrownBy(() -> listener.onMessage(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("artifact-cleanup-requested event missing required field: requestId");

        assertThat(useCase.command).isNull();
    }

    private ArtifactCleanupRequestedEvent validEvent() {
        return new ArtifactCleanupRequestedEvent(
                UUID.randomUUID(),
                "billing-service",
                OffsetDateTime.parse("2026-03-07T10:15:30Z")
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

    private static final class RecordingUseCase implements CleanupGeneratedArtifactUseCase {
        private Command command;

        @Override
        public void cleanup(Command command) {
            this.command = command;
        }
    }
}
