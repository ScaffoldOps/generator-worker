package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.minio.*;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalArtifactCleanupAdapterTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<MinioClient> provider(MinioClient client) {
        ObjectProvider<MinioClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    @Test
    void shouldRemoveExactObjectIdempotently() throws Exception {
        MinioClient client = mock(MinioClient.class);
        var adapter = new ExternalArtifactCleanupAdapter(provider(client), new ObjectMapper(), false,
                "https://hub.docker.com", "", "");
        adapter.deleteArtifact("s3://bucket/request/project.zip");
        adapter.deleteArtifact("s3://bucket/request/project.zip");
        var args = org.mockito.ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client, times(2)).removeObject(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo("bucket");
        assertThat(args.getValue().object()).isEqualTo("request/project.zip");
        verify(client, never()).listObjects(any());
    }

    @Test
    void shouldRemoveOnlyExplicitPrefixAndAcceptEmptyPrefixListing() throws Exception {
        MinioClient client = mock(MinioClient.class);
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn("request/project.zip");
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(new Result<>(item)), List.of());
        var adapter = new ExternalArtifactCleanupAdapter(provider(client), new ObjectMapper(), false,
                "https://hub.docker.com", "", "");
        adapter.deleteArtifact("s3://bucket/request/");
        adapter.deleteArtifact("s3://bucket/request/");
        var args = org.mockito.ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(client, times(2)).listObjects(args.capture());
        assertThat(args.getValue().prefix()).isEqualTo("request/");
        assertThat(args.getValue().recursive()).isTrue();
        verify(client).removeObject(any(RemoveObjectArgs.class));
        assertThatThrownBy(() -> adapter.deleteArtifact("s3://bucket/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldWarnWhenMinioAndHubAreNotConfigured() throws Exception {
        var adapter = new ExternalArtifactCleanupAdapter(provider(null), new ObjectMapper(), false,
                "http://127.0.0.1:1", "", "");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExternalArtifactCleanupAdapter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            adapter.deleteArtifact("s3://bucket/key");
            adapter.deleteImage("docker.io/owner/repo:tag");
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getFormattedMessage()).contains("Docker Hub tag cleanup skipped");
            });
        } finally { logger.detachAppender(appender); }
    }

    @Test
    void shouldParseOnlyExplicitHubTags() {
        for (String reference : List.of("docker.io/owner/repo:billing-id", "owner/repo:billing-id",
                "index.docker.io/owner/repo:billing-id", "registry-1.docker.io/owner/repo:billing-id")) {
            assertThat(ExternalArtifactCleanupAdapter.parseHubTag(reference))
                    .isEqualTo(new ExternalArtifactCleanupAdapter.HubTag("owner", "repo", "billing-id"));
        }
        for (String reference : List.of("ghcr.io/owner/repo:tag", "owner/repo", "owner/repo@sha256:abc",
                "docker.io/owner/../repo:tag", "owner/repo:tag?x=1", "https://docker.io/owner/repo:tag")) {
            assertThatThrownBy(() -> ExternalArtifactCleanupAdapter.parseHubTag(reference))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldExchangeCredentialsAndDeleteOnlyTagWithMissingTagSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger authCalls = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        java.util.List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getPath().equals("/v2/auth/token")) {
                var json = new ObjectMapper().readTree(exchange.getRequestBody());
                if ("owner".equals(json.path("identifier").asText()) && "secret".equals(json.path("secret").asText()))
                    authCalls.incrementAndGet();
                byte[] body = "{\"access_token\":\"test-token\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else if (exchange.getRequestMethod().equals("DELETE")
                    && exchange.getRequestURI().getPath().equals("/v2/namespaces/owner/repositories/repo/tags/billing-id/")
                    && "Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(deletes.incrementAndGet() == 1 ? 204 : 404, -1);
            } else { exchange.sendResponseHeaders(500, -1); }
            exchange.close();
        });
        server.start();
        try {
            var adapter = new ExternalArtifactCleanupAdapter(provider(null), new ObjectMapper(), true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "owner", "secret");
            adapter.deleteImage("docker.io/owner/repo:billing-id");
            adapter.deleteImage("docker.io/owner/repo:billing-id");
            assertThat(authCalls.get()).isEqualTo(2);
            assertThat(deletes.get()).isEqualTo(2);
            assertThat(requests).containsExactly("POST /v2/auth/token",
                    "DELETE /v2/namespaces/owner/repositories/repo/tags/billing-id/",
                    "POST /v2/auth/token", "DELETE /v2/namespaces/owner/repositories/repo/tags/billing-id/");
        } finally { server.stop(0); }
    }

    @Test
    void shouldFailAuthenticationWithoutLoggingResponseOrDeletingTag() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(401, -1); exchange.close();
        });
        server.start();
        try {
            var adapter = new ExternalArtifactCleanupAdapter(provider(null), new ObjectMapper(), true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "owner", "secret");
            assertThatThrownBy(() -> adapter.deleteImage("owner/repo:tag"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("secret");
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
