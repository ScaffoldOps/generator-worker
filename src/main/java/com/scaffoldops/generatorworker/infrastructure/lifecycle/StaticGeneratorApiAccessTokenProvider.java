package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import org.springframework.util.StringUtils;

import java.util.Optional;

final class StaticGeneratorApiAccessTokenProvider implements GeneratorApiAccessTokenProvider {

    private final String bearerToken;

    StaticGeneratorApiAccessTokenProvider(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    @Override
    public Optional<String> accessToken() {
        return StringUtils.hasText(bearerToken) ? Optional.of(bearerToken) : Optional.empty();
    }

    @Override
    public void invalidate() {
        // Static local-development tokens cannot be refreshed by the worker.
    }
}
