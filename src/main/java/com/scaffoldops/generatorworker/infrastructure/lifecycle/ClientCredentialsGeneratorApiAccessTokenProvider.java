package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

final class ClientCredentialsGeneratorApiAccessTokenProvider implements GeneratorApiAccessTokenProvider {

    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final RestTemplate restTemplate;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private CachedToken cachedToken;

    ClientCredentialsGeneratorApiAccessTokenProvider(
            RestTemplate restTemplate,
            String tokenUrl,
            String clientId,
            String clientSecret,
            Clock clock
    ) {
        this.restTemplate = restTemplate;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<String> accessToken() {
        if (cachedToken != null && cachedToken.isUsableAt(Instant.now(clock))) {
            return Optional.of(cachedToken.value());
        }

        TokenResponse response = restTemplate.postForObject(tokenUrl, tokenRequest(), TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            throw new IllegalStateException("Keycloak client credentials response did not include an access token");
        }

        cachedToken = new CachedToken(
                response.accessToken(),
                Instant.now(clock).plusSeconds(Math.max(response.expiresIn(), 0))
        );
        return Optional.of(cachedToken.value());
    }

    @Override
    public synchronized void invalidate() {
        cachedToken = null;
    }

    private HttpEntity<MultiValueMap<String, String>> tokenRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);

        return new HttpEntity<>(body, headers);
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean isUsableAt(Instant now) {
            return now.plus(REFRESH_SKEW).isBefore(expiresAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn
    ) {
    }
}
