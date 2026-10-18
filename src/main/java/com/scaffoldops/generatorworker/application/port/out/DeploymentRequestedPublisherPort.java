package com.scaffoldops.generatorworker.application.port.out;

import com.scaffoldops.generatorworker.domain.event.DeploymentRequestedEvent;

public interface DeploymentRequestedPublisherPort {

    void publish(DeploymentRequestedEvent event);
}
