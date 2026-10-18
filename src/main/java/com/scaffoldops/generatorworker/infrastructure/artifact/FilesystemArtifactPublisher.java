package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.application.port.out.ArtifactPublisher;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.artifact.storage-type", havingValue = "filesystem", matchIfMissing = true)
public class FilesystemArtifactPublisher implements ArtifactPublisher {
    private final ObjectMapper mapper;

    public FilesystemArtifactPublisher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String publish(GenerationArtifact artifact, String intendedImageRef) {
        try {
            ArtifactManifestWriter.finalizeManifest(mapper, artifact, artifact.artifactReference(), intendedImageRef);
            return artifact.artifactReference();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to finalize filesystem artifact", exception);
        }
    }
}
