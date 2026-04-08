package com.scaffoldops.generatorworker.infrastructure.lifecycle;

import com.scaffoldops.generatorworker.domain.model.GenerationLifecycleUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeneratorApiGenerationLifecycleAdapterTest {

    @Test
    void shouldNotCallHttpWhenLifecycleHttpIsDisabled() {
        RestTemplate restTemplate = new RestTemplate();
        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        false
                );

        assertThatCode(() -> adapter.updateStatus(update())).doesNotThrowAnyException();
    }

    @Test
    void shouldPostLifecycleUpdateWhenLifecycleHttpIsEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        GenerationLifecycleUpdate update = update();

        server.expect(requestTo("http://generator-api-service/internal/generation-requests/" + update.requestId() + "/status"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess());

        GeneratorApiGenerationLifecycleAdapter adapter =
                new GeneratorApiGenerationLifecycleAdapter(
                        restTemplate,
                        "http://generator-api-service",
                        "/internal/generation-requests/{requestId}/status",
                        true
                );

        adapter.updateStatus(update);

        server.verify();
    }

    private GenerationLifecycleUpdate update() {
        return new GenerationLifecycleUpdate(
                UUID.randomUUID(),
                "GENERATING",
                "started generation processing",
                OffsetDateTime.parse("2026-04-08T16:00:00Z")
        );
    }
}
