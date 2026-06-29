package com.scaffoldops.generatorworker.application.port.out;

import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;

public interface ImageBuilderPort {

    void build(GenerationArtifact artifact);
}
