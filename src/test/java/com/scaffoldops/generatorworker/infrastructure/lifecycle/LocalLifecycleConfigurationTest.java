package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class LocalLifecycleConfigurationTest {

    @Value("${app.lifecycle.base-url}")
    private String baseUrl;

    @Value("${app.lifecycle.http-enabled}")
    private boolean httpEnabled;

    @Value("${app.lifecycle.status-update-path}")
    private String statusUpdatePath;

    @Value("${app.lifecycle.bearer-token}")
    private String bearerToken;

    @Value("${app.lifecycle.auth.mode}")
    private String authMode;

    @Test
    void shouldEnableGeneratorApiLifecycleCallbackForLocalProfile() {
        assertThat(httpEnabled).isTrue();
        assertThat(baseUrl).isEqualTo("http://localhost:8081/api/generator/v1");
        assertThat(statusUpdatePath).isEqualTo("/internal/generation-requests/{requestId}/status");
        assertThat(bearerToken).isEmpty();
        assertThat(authMode).isEqualTo("static-token");
    }
}
