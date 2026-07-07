package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GeneratorApiGenerationLifecycleAdapterTest {

    @Test
    void shouldNotCallHttpWhenLifecycleHttpIsDisabled() {
        RestTemplate restTemplate = new RestTemplate();
        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        false,
                        ""
                );

        assertThatCode(() -> adapter.updateStatus(update("GENERATING"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVED", "DEPLOYMENT_REQUESTED"})
    void shouldNotPostStatusesOutsideGeneratorApiLifecycleContract(String status) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true,
                        ""
                );

        adapter.updateStatus(update(status));

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GENERATING", "GENERATED", "FAILED"})
    void shouldPatchSupportedLifecycleStatusWhenLifecycleHttpIsEnabled(String status) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GenerationLifecycleUpdate update = update(status);

        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.message").value(update.message()))
                .andExpect(jsonPath("$.requestId").doesNotExist())
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.occurredAt").doesNotExist())
                .andRespond(withSuccess());

        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true,
                        ""
                );

        adapter.updateStatus(update);

        server.verify();
    }

    @Test
    void shouldSendArtifactAndImageReferencesForGeneratedStatus() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GenerationLifecycleUpdate update = new GenerationLifecycleUpdate(
                UUID.randomUUID(),
                "GENERATED",
                "Generation and image build completed",
                "file:///tmp/billing-service/",
                "scaffoldops/billing-service:request-id"
        );

        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.artifactRef").value(update.artifactRef()))
                .andExpect(jsonPath("$.imageRef").value(update.imageRef()))
                .andRespond(withSuccess());

        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true,
                        ""
                );

        adapter.updateStatus(update);

        server.verify();
    }

    @Test
    void shouldSendConfiguredBearerToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GenerationLifecycleUpdate update = update("GENERATING");

        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer local-service-token"))
                .andRespond(withSuccess());

        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true,
                        "local-service-token"
                );

        adapter.updateStatus(update);

        server.verify();
    }

    @Test
    void shouldSendProviderBearerToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GenerationLifecycleUpdate update = update("GENERATING");

        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer client-credentials-token"))
                .andRespond(withSuccess());

        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true,
                        new RecordingAccessTokenProvider("client-credentials-token", "client-credentials-token")
                );

        adapter.updateStatus(update);

        server.verify();
    }

    @Test
    void shouldRefreshTokenAndRetryOnceWhenGeneratorApiReturnsUnauthorized() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GenerationLifecycleUpdate update = update("GENERATING");

        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer expired-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer refreshed-token"))
                .andRespond(withSuccess());

        RecordingAccessTokenProvider accessTokenProvider =
                new RecordingAccessTokenProvider("expired-token", "refreshed-token");
        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true,
                        accessTokenProvider
                );

        adapter.updateStatus(update);

        assertThatCode(() -> server.verify()).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(accessTokenProvider.invalidateCalls).isEqualTo(1);
    }

    @Test
    void shouldUseStaticTokenFallbackWhenClientCredentialsAreNotConfigured() {
        GeneratorApiAccessTokenProvider provider =
                GeneratorApiAccessTokenProviderFactory.create(
                        new RestTemplate(),
                        "client-credentials",
                        "",
                        "",
                        "",
                        "local-service-token",
                        java.time.Clock.systemUTC()
                );

        org.assertj.core.api.Assertions.assertThat(provider.accessToken()).contains("local-service-token");
    }

    private GenerationLifecycleUpdate update(String status) {
        return new GenerationLifecycleUpdate(
                UUID.randomUUID(),
                status,
                "generation lifecycle status changed to " + status,
                null,
                null
        );
    }

    private static final class RecordingAccessTokenProvider implements GeneratorApiAccessTokenProvider {

        private final String initialToken;
        private final String refreshedToken;
        private int invalidateCalls;

        private RecordingAccessTokenProvider(String initialToken, String refreshedToken) {
            this.initialToken = initialToken;
            this.refreshedToken = refreshedToken;
        }

        @Override
        public Optional<String> accessToken() {
            return Optional.of(invalidateCalls == 0 ? initialToken : refreshedToken);
        }

        @Override
        public void invalidate() {
            invalidateCalls++;
        }
    }
}
