package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessGenerationRequestServiceTest {

    @Test
    void shouldMarkReceivedGeneratingAndGeneratedForPlaceholderWorkflow() {
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(false);
        ProcessGenerationRequestService service = new ProcessGenerationRequestService(lifecyclePort, projectGenerationPort);

        service.process(command());

        assertThat(projectGenerationPort.invocations).isEqualTo(1);
        assertThat(lifecyclePort.updates).hasSize(3);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("RECEIVED");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(2).status()).isEqualTo("GENERATED");
    }

    @Test
    void shouldMarkFailedWhenPlaceholderGenerationThrows() {
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(true);
        ProcessGenerationRequestService service = new ProcessGenerationRequestService(lifecyclePort, projectGenerationPort);

        assertThatThrownBy(() -> service.process(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("placeholder failure");

        assertThat(lifecyclePort.updates).hasSize(3);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("RECEIVED");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(2).status()).isEqualTo("FAILED");
    }

    private ProcessGenerationRequestUseCase.Command command() {
        return new ProcessGenerationRequestUseCase.Command(
                UUID.randomUUID(),
                "billing-service",
                "spring-boot-hexagonal",
                true,
                true,
                true,
                false,
                "kubernetes",
                "REQUESTED",
                OffsetDateTime.now()
        );
    }

    private static final class RecordingLifecyclePort implements GenerationLifecyclePort {
        private final List<GenerationLifecycleUpdate> updates = new ArrayList<>();

        @Override
        public void updateStatus(GenerationLifecycleUpdate update) {
            updates.add(update);
        }
    }

    private static final class RecordingProjectGenerationPort implements ProjectGenerationPort {
        private final boolean fail;
        private int invocations;

        private RecordingProjectGenerationPort(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void generate(GenerationRequest request) {
            invocations++;
            if (fail) {
                throw new IllegalStateException("placeholder failure");
            }
        }
    }
}
