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
        order.verify(lifecycle).updateStatus(argThat(update -> update.status().equals("RECEIVED")));
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
    void shouldPreserveFilesystemReferenceAndNullImageWhenBuildDisabled() {
        when(generator.generate(any())).thenReturn(artifact);
        when(publisher.publish(artifact, null)).thenReturn(artifact.artifactReference());
        service.process(command());
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("GENERATED")
                && artifact.artifactReference().equals(update.artifactRef()) && update.imageRef() == null));
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
        assertThatThrownBy(() -> service.process(command())).hasMessage(failure);
        verifyFailed();
    }

    private void prepare() {
        when(generator.generate(any())).thenReturn(artifact);
        when(builder.intendedImageReference(artifact)).thenReturn(artifact.imageName());
        when(publisher.publish(artifact, artifact.imageName())).thenReturn(publishedRef);
        when(builder.build(artifact)).thenReturn(artifact.imageName());
    }

    private void verifyFailed() {
        verify(lifecycle).updateStatus(argThat(update -> update.status().equals("FAILED")));
        verify(lifecycle, never()).updateStatus(argThat(update -> update.status().equals("GENERATED")));
    }

    private ProcessGenerationRequestUseCase.Command command() {
        return new ProcessGenerationRequestUseCase.Command(id, "billing", "spring-boot-hello-world",
                true, true, true, false, "kubernetes", "REQUESTED", OffsetDateTime.now());
    }
}
