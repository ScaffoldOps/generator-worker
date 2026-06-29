package com.scaffoldops.generatorworker.application.port.out;

import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;

public interface ProjectGenerationPort {

    GenerationArtifact generate(GenerationRequest request);
}
