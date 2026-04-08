package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.DeploymentRequestedPublisherPort;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
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
    void shouldMarkReceivedGeneratingGeneratedAndDeploymentRequestedForSuccessfulWorkflow() {
        RecordingDeploymentRequestedPublisher publisher = new RecordingDeploymentRequestedPublisher(false);
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(false);
        ProcessGenerationRequestService service = new ProcessGenerationRequestService(publisher, lifecyclePort, projectGenerationPort);

        service.process(command());

        assertThat(projectGenerationPort.invocations).isEqualTo(1);
        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0).artifactReference()).isEqualTo(projectGenerationPort.artifact.artifactReference());
        assertThat(lifecyclePort.updates).hasSize(4);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("RECEIVED");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(2).status()).isEqualTo("GENERATED");
        assertThat(lifecyclePort.updates.get(3).status()).isEqualTo("DEPLOYMENT_REQUESTED");
    }

    @Test
    void shouldMarkFailedWhenGenerationThrows() {
        RecordingDeploymentRequestedPublisher publisher = new RecordingDeploymentRequestedPublisher(false);
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(true);
        ProcessGenerationRequestService service = new ProcessGenerationRequestService(publisher, lifecyclePort, projectGenerationPort);

        assertThatThrownBy(() -> service.process(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("generation failure");

        assertThat(lifecyclePort.updates).hasSize(3);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("RECEIVED");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(2).status()).isEqualTo("FAILED");
        assertThat(lifecyclePort.updates.get(2).detail()).contains("generation stage failed");
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void shouldMarkFailedWhenDeploymentPublicationThrows() {
        RecordingDeploymentRequestedPublisher publisher = new RecordingDeploymentRequestedPublisher(true);
        RecordingLifecyclePort lifecyclePort = new RecordingLifecyclePort();
        RecordingProjectGenerationPort projectGenerationPort = new RecordingProjectGenerationPort(false);
        ProcessGenerationRequestService service = new ProcessGenerationRequestService(publisher, lifecyclePort, projectGenerationPort);

        assertThatThrownBy(() -> service.process(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failure");

        assertThat(projectGenerationPort.invocations).isEqualTo(1);
        assertThat(lifecyclePort.updates).hasSize(4);
        assertThat(lifecyclePort.updates.get(0).status()).isEqualTo("RECEIVED");
        assertThat(lifecyclePort.updates.get(1).status()).isEqualTo("GENERATING");
        assertThat(lifecyclePort.updates.get(2).status()).isEqualTo("GENERATED");
        assertThat(lifecyclePort.updates.get(3).status()).isEqualTo("FAILED");
        assertThat(lifecyclePort.updates.get(3).detail()).contains("deployment-request publication failed");
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
        private GenerationArtifact artifact;

        private RecordingProjectGenerationPort(boolean fail) {
            this.fail = fail;
        }

        @Override
        public GenerationArtifact generate(GenerationRequest request) {
            invocations++;
            if (fail) {
                throw new IllegalStateException("generation failure");
            }
            artifact = new GenerationArtifact(
                    request.requestId(),
                    "generation-manifest",
                    "file:///tmp/manifest-" + request.requestId() + ".json",
                    OffsetDateTime.now()
            );
            return artifact;
        }
    }

    private static final class RecordingDeploymentRequestedPublisher implements DeploymentRequestedPublisherPort {
        private final boolean fail;
        private final List<DeploymentRequestedEvent> events = new ArrayList<>();

        private RecordingDeploymentRequestedPublisher(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void publish(DeploymentRequestedEvent event) {
            events.add(event);
            if (fail) {
                throw new IllegalStateException("publish failure");
            }
        }
    }
}
