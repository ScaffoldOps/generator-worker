package com.scaffoldops.generatorworker.application.port.out;

import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;

public interface ArtifactPublisher {
    String publish(GenerationArtifact artifact, String intendedImageRef);
}
