package com.scaffoldops.generatorworker.application.port.out;

import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;

public interface ImageBuilderPort {

    default String intendedImageReference(GenerationArtifact artifact) {
        return artifact.imageName();
    }

    String build(GenerationArtifact artifact);
}
