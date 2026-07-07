package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ClientCredentialsGeneratorApiAccessTokenProviderTest {

    @Test
    void shouldObtainAndCacheToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ClientCredentialsGeneratorApiAccessTokenProvider provider =
                new ClientCredentialsGeneratorApiAccessTokenProvider(
                        restTemplate,
                        "http://keycloak/realms/scaffoldops/protocol/openid-connect/token",
                        "scaffoldops-generator-worker",
                        "client-secret",
                        new MutableClock(Instant.parse("2026-07-07T10:00:00Z"))
                );

        server.expect(requestTo("http://keycloak/realms/scaffoldops/protocol/openid-connect/token"))
                .andExpect(header("Content-Type", containsString(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("client_id=scaffoldops-generator-worker")))
                .andExpect(content().string(containsString("client_secret=client-secret")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token-1\",\"expires_in\":120,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON
                ));

        assertThat(provider.accessToken()).contains("token-1");
        assertThat(provider.accessToken()).contains("token-1");

        server.verify();
    }

    @Test
    void shouldRefreshTokenBeforeExpiration() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MutableClock clock = new MutableClock(Instant.parse("2026-07-07T10:00:00Z"));
        ClientCredentialsGeneratorApiAccessTokenProvider provider =
                new ClientCredentialsGeneratorApiAccessTokenProvider(
                        restTemplate,
                        "http://keycloak/token",
                        "scaffoldops-generator-worker",
                        "client-secret",
                        clock
                );

        server.expect(requestTo("http://keycloak/token"))
                .andRespond(withSuccess("{\"access_token\":\"token-1\",\"expires_in\":120}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://keycloak/token"))
                .andRespond(withSuccess("{\"access_token\":\"token-2\",\"expires_in\":120}", MediaType.APPLICATION_JSON));

        assertThat(provider.accessToken()).contains("token-1");

        clock.setInstant(Instant.parse("2026-07-07T10:01:31Z"));

        assertThat(provider.accessToken()).contains("token-2");
        server.verify();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
