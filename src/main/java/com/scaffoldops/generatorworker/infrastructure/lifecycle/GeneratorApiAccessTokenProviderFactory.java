package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

final class GeneratorApiAccessTokenProviderFactory {

    private static final String CLIENT_CREDENTIALS = "client-credentials";
    private static final String STATIC_TOKEN = "static-token";

    private GeneratorApiAccessTokenProviderFactory() {
    }

    static GeneratorApiAccessTokenProvider create(
            RestTemplate tokenRestTemplate,
            String authMode,
            String tokenUrl,
            String clientId,
            String clientSecret,
            String staticBearerToken,
            Clock clock
    ) {
        if (STATIC_TOKEN.equals(authMode)) {
            return new StaticGeneratorApiAccessTokenProvider(staticBearerToken);
        }

        if (!CLIENT_CREDENTIALS.equals(authMode)) {
            throw new IllegalArgumentException("Unsupported generator-api auth mode: " + authMode);
        }

        if (StringUtils.hasText(tokenUrl) && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret)) {
            return new ClientCredentialsGeneratorApiAccessTokenProvider(
                    tokenRestTemplate,
                    tokenUrl,
                    clientId,
                    clientSecret,
                    clock
            );
        }

        return new StaticGeneratorApiAccessTokenProvider(staticBearerToken);
    }
}
