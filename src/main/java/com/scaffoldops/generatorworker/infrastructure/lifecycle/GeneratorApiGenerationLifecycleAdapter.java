package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class GeneratorApiGenerationLifecycleAdapter implements GenerationLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(GeneratorApiGenerationLifecycleAdapter.class);

    private final RestTemplate restTemplate;
    private final String lifecycleBaseUrl;
    private final String lifecycleStatusUpdatePath;
    private final boolean lifecycleHttpEnabled;

    @Autowired
    public GeneratorApiGenerationLifecycleAdapter(
            @Value("${app.lifecycle.base-url:http://generator-api-service}") String lifecycleBaseUrl,
            @Value("${app.lifecycle.status-update-path:/internal/generation-requests/{requestId}/status}") String lifecycleStatusUpdatePath,
            @Value("${app.lifecycle.http-enabled:false}") boolean lifecycleHttpEnabled
    ) {
        this(new RestTemplate(), lifecycleBaseUrl, lifecycleStatusUpdatePath, lifecycleHttpEnabled);
    }

    GeneratorApiGenerationLifecycleAdapter(
            RestTemplate restTemplate,
            String lifecycleBaseUrl,
            String lifecycleStatusUpdatePath,
            boolean lifecycleHttpEnabled
    ) {
        this.restTemplate = restTemplate;
        this.lifecycleBaseUrl = lifecycleBaseUrl;
        this.lifecycleStatusUpdatePath = lifecycleStatusUpdatePath;
        this.lifecycleHttpEnabled = lifecycleHttpEnabled;
    }

    @Override
    public void updateStatus(GenerationLifecycleUpdate update) {
        if (!lifecycleHttpEnabled) {
            log.info(
                    "Lifecycle HTTP disabled requestId={} status={} targetBaseUrl={} detail={} workerService=generator-worker",
                    update.requestId(),
                    update.status(),
                    lifecycleBaseUrl,
                    update.detail()
            );
            return;
        }

        try {
            restTemplate.postForEntity(
                    lifecycleBaseUrl + lifecycleStatusUpdatePath,
                    new HttpEntity<>(payload(update), headers()),
                    Void.class,
                    Map.of("requestId", update.requestId())
            );
            log.info(
                    "Posted lifecycle update requestId={} status={} targetBaseUrl={} workerService=generator-worker",
                    update.requestId(),
                    update.status(),
                    lifecycleBaseUrl
            );
        } catch (RestClientException exception) {
            throw new IllegalStateException("generator-api lifecycle update failed for requestId=" + update.requestId(), exception);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> payload(GenerationLifecycleUpdate update) {
        return Map.of(
                "requestId", update.requestId(),
                "status", update.status(),
                "detail", update.detail(),
                "occurredAt", update.occurredAt().toString()
        );
    }
}
