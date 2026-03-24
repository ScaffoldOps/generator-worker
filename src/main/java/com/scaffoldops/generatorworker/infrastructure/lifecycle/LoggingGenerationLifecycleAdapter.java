package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoggingGenerationLifecycleAdapter implements GenerationLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(LoggingGenerationLifecycleAdapter.class);

    private final String lifecycleBaseUrl;

    public LoggingGenerationLifecycleAdapter(
            @Value("${app.lifecycle.base-url:http://generator-api-service}") String lifecycleBaseUrl
    ) {
        this.lifecycleBaseUrl = lifecycleBaseUrl;
    }

    @Override
    public void updateStatus(GenerationLifecycleUpdate update) {
        log.info(
                "Placeholder lifecycle update for requestId={} status={} targetBaseUrl={} detail={}",
                update.requestId(),
                update.status(),
                lifecycleBaseUrl,
                update.detail()
        );
    }
}
