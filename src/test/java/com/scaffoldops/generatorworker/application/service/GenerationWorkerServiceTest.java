package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationJobUseCase;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationWorkerServiceTest {

    @Test
    void shouldMarkGeneratingAndGeneratedForPlaceholderWorkflow() {
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(false);
        GenerationWorkerService service = new GenerationWorkerService(lifecyclePort, projectGenerationPort);

        service.process(command());

        assertThat(projectGenerationPort.invocations).isEqualTo(1);
        assertThat(lifecyclePort.updates).hasSize(2);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("GENERATED");
    }

    @Test
    void shouldMarkFailedWhenPlaceholderGenerationThrows() {
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(true);
        GenerationWorkerService service = new GenerationWorkerService(lifecyclePort, projectGenerationPort);

        assertThatThrownBy(() -> service.process(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("placeholder failure");

        assertThat(lifecyclePort.updates).hasSize(2);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("FAILED");
    }

    private ProcessGenerationJobUseCase.Command command() {
        return new ProcessGenerationJobUseCase.Command(
                UUID.randomUUID(),
                "billing-service",
                "spring-boot-hexagonal",
                true,
                true,
                true,
                false,
                "kubernetes",
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
        public void generate(com.scaffoldops.generatorworker.domain.model.GenerationJob job) {
            invocations++;
            if (fail) {
                throw new IllegalStateException("placeholder failure");
            }
        }
    }
}
