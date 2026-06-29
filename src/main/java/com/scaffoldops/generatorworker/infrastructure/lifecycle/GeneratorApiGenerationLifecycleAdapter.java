package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Set;

@Component
public class GeneratorApiGenerationLifecycleAdapter implements GenerationLifecyclePort {

    private static final Logger log = LoggerFactory.getLogger(GeneratorApiGenerationLifecycleAdapter.class);
    private static final Set<String> HTTP_CALLBACK_STATUSES = Set.of("GENERATING", "GENERATED", "FAILED");

    private final RestTemplate restTemplate;
    private final String lifecycleBaseUrl;
    private final String lifecycleStatusUpdatePath;
    private final boolean lifecycleHttpEnabled;
    private final String lifecycleBearerToken;

    @Autowired
    public GeneratorApiGenerationLifecycleAdapter(
            @Value("${app.lifecycle.base-url:http://generator-api-service/api/generator/v1}") String lifecycleBaseUrl,
            @Value("${app.lifecycle.status-update-path:/internal/generation-requests/{requestId}/status}") String lifecycleStatusUpdatePath,
            @Value("${app.lifecycle.http-enabled:false}") boolean lifecycleHttpEnabled,
            @Value("${app.lifecycle.bearer-token:}") String lifecycleBearerToken
    ) {
        this(
                new RestTemplate(new JdkClientHttpRequestFactory()),
                lifecycleBaseUrl,
                lifecycleStatusUpdatePath,
                lifecycleHttpEnabled,
                lifecycleBearerToken
        );
    }

    GeneratorApiGenerationLifecycleAdapter(
            RestTemplate restTemplate,
            String lifecycleBaseUrl,
            String lifecycleStatusUpdatePath,
            boolean lifecycleHttpEnabled,
            String lifecycleBearerToken
    ) {
        this.restTemplate = restTemplate;
        this.lifecycleBaseUrl = lifecycleBaseUrl;
        this.lifecycleStatusUpdatePath = lifecycleStatusUpdatePath;
        this.lifecycleHttpEnabled = lifecycleHttpEnabled;
        this.lifecycleBearerToken = lifecycleBearerToken;
    }

    @Override
    public void updateStatus(GenerationLifecycleUpdate update) {
        if (!HTTP_CALLBACK_STATUSES.contains(update.status())) {
            log.debug(
                    "Skipping lifecycle HTTP callback requestId={} status={} workerService=generator-worker",
                    update.requestId(),
                    update.status()
            );
            return;
        }

        if (!lifecycleHttpEnabled) {
            log.info(
                    "Lifecycle HTTP disabled requestId={} status={} targetBaseUrl={} message={} workerService=generator-worker",
                    update.requestId(),
                    update.status(),
                    lifecycleBaseUrl,
                    update.message()
            );
            return;
        }

        try {
            restTemplate.exchange(
                    lifecycleBaseUrl + lifecycleStatusUpdatePath,
                    HttpMethod.PATCH,
                    new HttpEntity<>(payload(update), headers()),
                    Void.class,
                    update.requestId()
            );
            log.info(
                    "Patched lifecycle update requestId={} status={} targetBaseUrl={} workerService=generator-worker",
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
        if (StringUtils.hasText(lifecycleBearerToken)) {
            headers.setBearerAuth(lifecycleBearerToken);
        }
        return headers;
    }

    private CallbackRequest payload(GenerationLifecycleUpdate update) {
        return new CallbackRequest(
                update.status(),
                update.message(),
                update.artifactRef(),
                update.imageRef()
        );
    }

    private record CallbackRequest(
            String status,
            String message,
            String artifactRef,
            String imageRef
    ) {
    }
}
