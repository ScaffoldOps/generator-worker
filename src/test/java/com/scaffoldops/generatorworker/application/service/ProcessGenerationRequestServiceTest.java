package com.scaffoldops.generatorworker.application.service;

import com.scaffoldops.generatorworker.application.port.in.ProcessGenerationRequestUseCase;
import com.scaffoldops.generatorworker.application.port.out.*;
import com.scaffoldops.generatorworker.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessGenerationRequestServiceTest {
    private final ArtifactPublisher publisher = mock(ArtifactPublisher.class);
    private final GenerationLifecyclePort lifecycle = mock(GenerationLifecyclePort.class);
    private final ImageBuilderPort builder = mock(ImageBuilderPort.class);
    private final ProjectGenerationPort generator = mock(ProjectGenerationPort.class);
    private final ProcessGenerationRequestService service =
            new ProcessGenerationRequestService(publisher, lifecycle, builder, generator);
    private final UUID id = UUID.randomUUID();
    private final GenerationArtifact artifact = new GenerationArtifact(id, "spring-boot-project",
            "file:///tmp/billing/", "billing", "registry.local/generated/billing:" + id, OffsetDateTime.now());
    private final String publishedRef = "s3://scaffoldops-artifacts/" + id + "/project.zip";

    @Test
    void shouldPublishArtifactAndBuildBeforeGeneratedWithBothReferences() {
        prepare();
        service.process(command());
        var order = inOrder(generator, publisher, builder, lifecycle);
        order.verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATING")));
        order.verify(generator).generate(any());
        order.verify(builder).intendedImageReference(artifact);
        order.verify(publisher).publish(artifact, artifact.imageName());
        order.verify(builder).build(artifact);
        order.verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATED")
                && publishedRef.equals(update.artifactRef()) && artifact.imageName().equals(update.imageRef())));
        verifyNoMoreInteractions(lifecycle);
    }

    @Test
    void shouldFailWithArtifactPreservedWhenImageReferenceMissing() {
        when(generator.generate(any())).thenReturn(artifact);
        when(publisher.publish(artifact, null)).thenReturn(artifact.artifactReference());
        assertThatThrownBy(() -> service.process(command())).hasMessageContaining("blank imageRef");
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")
                && artifact.artifactReference().equals(update.artifactRef()) && update.imageRef() == null
                && update.failureStage().equals("IMAGE_BUILD")));
        verify(builder).build(artifact);
        verify(lifecycle, never()).updateStatus(argThat(update -> update.status().equals("GENERATED")));
    }

    @Test
    void shouldFailWhenGenerationFails() {
        when(generator.generate(any())).thenThrow(new IllegalStateException("generation failure"));
        assertThatThrownBy(() -> service.process(command())).hasMessage("generation failure");
        verifyFailed();
        verifyNoInteractions(publisher, builder);
    }

    @Test
    void shouldFailWhenArtifactUploadFails() {
        prepare();
        when(publisher.publish(any(), any())).thenThrow(new IllegalStateException("upload failure"));
        assertThatThrownBy(() -> service.process(command())).hasMessage("upload failure");
        verifyFailed();
        verify(builder, never()).build(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker build failed", "docker push failed"})
    void shouldFailWhenBuildOrPushFails(String failure) {
        prepare();
        when(builder.build(artifact)).thenThrow(new IllegalStateException(failure));
        assertThatThrownBy(() -> service.process(command())).hasMessageContaining(failure);
        verifyFailed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "generationStatus"})
    void shouldProcessSerializedApiContractThroughGenerated(String statusField) throws Exception {
        prepare();
        var listener = new com.scaffoldops.generatorworker.infrastructure.messaging.kafka.GenerationRequestedKafkaListener(
                service, new com.scaffoldops.generatorworker.infrastructure.config.KafkaTopicProperties(
                        "generation-requested", "deployment-requested", "generation-requested-dlt",
                        "artifact-cleanup-requested"));
        try (var fixture = getClass().getResourceAsStream("/contracts/generation-requested.json");
             var deserializer = new org.springframework.kafka.support.serializer.JsonDeserializer<>(
                     com.scaffoldops.generatorworker.domain.event.GenerationRequestedEvent.class, false)) {
            String payload = new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"generationStatus\"", "\"" + statusField + "\"");
            var event = deserializer.deserialize("generation-requested",
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            listener.onMessage(event);
            var order = inOrder(lifecycle, generator, publisher, builder);
            order.verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATING")
                    && update.requestId().equals(event.requestId())));
            order.verify(generator).generate(argThat(request -> request.requestId().equals(event.requestId())
                    && request.name().equals(event.name())));
            order.verify(builder).intendedImageReference(artifact);
            order.verify(publisher).publish(artifact, artifact.imageName());
            order.verify(builder).build(artifact);
            order.verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATED")
                    && publishedRef.equals(update.artifactRef()) && artifact.imageName().equals(update.imageRef())));
            verifyNoMoreInteractions(lifecycle);
        }
    }

    @Test
    void shouldRetryTransientImageFailureAndCompleteGeneration() {
        prepare();
        when(builder.build(artifact)).thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(artifact.imageName());
        service.process(command());
        verify(builder, times(2)).build(artifact);
        verify(publisher).publish(artifact, artifact.imageName());
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATED")
                && update.retryCount() == 1 && publishedRef.equals(update.artifactRef())
                && artifact.imageName().equals(update.imageRef()) && update.failureStage() == null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE_BUILD", "IMAGE_PUSH"})
    void shouldExhaustImageRetriesAndPreserveUploadedArtifact(String stage) {
        prepare();
        when(builder.build(artifact)).thenThrow(new ImageGenerationException(stage, "registry unavailable", null, true));
        assertThatThrownBy(() -> service.process(command())).hasMessageContaining("after 3 attempt(s)");
        verify(builder, times(3)).build(artifact);
        verify(publisher).publish(artifact, artifact.imageName());
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")
                && publishedRef.equals(update.artifactRef()) && update.imageRef() == null
                && stage.equals(update.failureStage()) && update.retryCount() == 2));
        verify(lifecycle, never()).updateStatus(argThat(update -> update.status().equals("GENERATED")));
    }

    @Test
    void shouldReportCallbackFailureAndRetainOriginalException() {
        prepare();
        doThrow(new IllegalStateException("callback unavailable")).when(lifecycle)
                .updateStatus(argThat(update -> update.status().equals("GENERATED")));
        assertThatThrownBy(() -> service.process(command())).hasMessage("callback unavailable");
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")
                && "CALLBACK".equals(update.failureStage()) && publishedRef.equals(update.artifactRef())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMAGE_BUILD", "IMAGE_PUSH"})
    void shouldNotRetryDisabledImagePipeline(String stage) {
        prepare();
        when(builder.build(artifact)).thenThrow(new ImageGenerationException(stage,
                "Docker image generation is disabled; cannot complete generation", null, false));
        assertThatThrownBy(() -> service.process(command())).hasMessageContaining("disabled");
        verify(builder).build(artifact);
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")
                && stage.equals(update.failureStage()) && update.retryCount() == 0
                && publishedRef.equals(update.artifactRef()) && update.imageRef() == null));
    }

    @Test
    void shouldRespectConfiguredMaximumAttempts() {
        prepare();
        when(builder.build(artifact)).thenThrow(new IllegalStateException("build failed"));
        var singleAttempt = new ProcessGenerationRequestService(publisher, lifecycle, builder, generator, 1, 0);
        assertThatThrownBy(() -> singleAttempt.process(command())).hasMessageContaining("after 1 attempt(s)");
        verify(builder).build(artifact);
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")
                && update.retryCount() == 0));
    }

    @Test
    void shouldRejectBlankPublishedArtifactBeforeBuildingImage() {
        when(generator.generate(any())).thenReturn(artifact);
        when(publisher.publish(any(), any())).thenReturn(" ");
        assertThatThrownBy(() -> service.process(command())).hasMessageContaining("blank artifactRef");
        verify(builder, never()).build(any());
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")
                && "ARTIFACT_UPLOAD".equals(update.failureStage()) && update.artifactRef() == null));
    }

    private void prepare() {
        when(generator.generate(any())).thenReturn(artifact);
        when(builder.intendedImageReference(artifact)).thenReturn(artifact.imageName());
        when(publisher.publish(artifact, artifact.imageName())).thenReturn(publishedRef);
        when(builder.build(artifact)).thenReturn(artifact.imageName());
    }

    private void verifyFailed() {
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATION_FAILED")));
        verify(lifecycle, never()).updateStatus(argThat(update -> update.status().equals("GENERATED")));
    }

    private ProcessGenerationRequestUseCase.Command command() {
        return new ProcessGenerationRequestUseCase.Command(id, "billing", "spring-boot-hello-world",
                true, true, true, false, "kubernetes", "RECEIVED", OffsetDateTime.now());
    }
}
