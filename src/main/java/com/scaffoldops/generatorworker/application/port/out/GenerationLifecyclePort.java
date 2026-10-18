package com.scaffoldops.generatorworker.application.port.out;

import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;

public interface GenerationLifecyclePort {

    void updateStatus(GenerationLifecycleUpdate update);
}
