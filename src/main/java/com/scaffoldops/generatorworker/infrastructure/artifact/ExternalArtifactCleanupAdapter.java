package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.application.port.out.ExternalArtifactCleanup;
import io.minio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ExternalArtifactCleanupAdapter implements ExternalArtifactCleanup {
    private static final Logger log = LoggerFactory.getLogger(ExternalArtifactCleanupAdapter.class);
    private static final Pattern HUB_TAG = Pattern.compile(
            "^(?:docker\\.io/|index\\.docker\\.io/|registry-1\\.docker\\.io/)?([a-z0-9]+(?:[._-][a-z0-9]+)*)/([a-z0-9]+(?:[._-][a-z0-9]+)*):([A-Za-z0-9_][A-Za-z0-9_.-]{0,127})$");
    private final ObjectProvider<MinioClient> minio;
    private final ObjectMapper mapper;
    private final boolean hubEnabled;
    private final String hubBaseUrl;
    private final String username;
    private final String password;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ExternalArtifactCleanupAdapter(ObjectProvider<MinioClient> minio, ObjectMapper mapper,
            @Value("${app.cleanup.docker-hub.enabled:false}") boolean hubEnabled,
            @Value("${app.cleanup.docker-hub.base-url:https://hub.docker.com}") String hubBaseUrl,
            @Value("${app.image-builder.username:}") String username,
            @Value("${app.image-builder.password:}") String password) {
        this.minio = minio;
        this.mapper = mapper;
        this.hubEnabled = hubEnabled;
        this.hubBaseUrl = hubBaseUrl.replaceAll("/+$", "");
        URI base = URI.create(this.hubBaseUrl);
        if (!("https".equals(base.getScheme()) || ("http".equals(base.getScheme())
                && ("localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost()))))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Docker Hub API requires HTTPS (HTTP allowed only for loopback tests)");
        }
        this.username = username;
        this.password = password;
    }

    @Override
    public void deleteArtifact(String reference) throws Exception {
        URI uri = URI.create(reference);
        if (!"s3".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1) {
            throw new IllegalArgumentException("unsupported or invalid artifact reference");
        }
        String key = uri.getPath().substring(1);
        if (key.isBlank()) throw new IllegalArgumentException("bucket-wide cleanup is forbidden");
        MinioClient client = minio.getIfAvailable();
        if (client == null) {
            log.warn("MinIO artifact cleanup skipped: configure app.artifact.storage-type=minio and MinIO credentials");
            return;
        }
        // A trailing slash explicitly denotes a prefix. Never guess a prefix for a missing object.
        if (key.endsWith("/")) {
            for (var result : client.listObjects(ListObjectsArgs.builder().bucket(uri.getHost())
                    .prefix(key).recursive(true).build())) {
                client.removeObject(RemoveObjectArgs.builder().bucket(uri.getHost())
                        .object(result.get().objectName()).build());
            }
        } else {
            client.removeObject(RemoveObjectArgs.builder().bucket(uri.getHost()).object(key).build());
        }
    }

    public record HubTag(String namespace, String repository, String tag) { }

    public static HubTag parseHubTag(String reference) {
        var match = HUB_TAG.matcher(reference);
        if (!match.matches()) throw new IllegalArgumentException("unsupported Docker Hub tag reference");
        return new HubTag(match.group(1), match.group(2), match.group(3));
    }

    @Override
    public void deleteImage(String reference) throws Exception {
        if (!hubEnabled || username.isBlank() || password.isBlank()) {
            log.warn("Docker Hub tag cleanup skipped: enable app.cleanup.docker-hub.enabled and configure DOCKER_USERNAME/DOCKER_PASSWORD with delete permission");
            return;
        }
        HubTag tag = parseHubTag(reference); // Reject digests/other registries instead of deleting shared manifests.
        HttpResponse<String> auth = send(HttpRequest.newBuilder(URI.create(hubBaseUrl + "/v2/auth/token"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                        Map.of("identifier", username, "secret", password)))));
        requireSuccess(auth.statusCode(), "authentication");
        String token = mapper.readTree(auth.body()).path("access_token").asText();
        if (token.isBlank()) throw new IllegalStateException("Docker Hub returned no access token");
        HttpResponse<String> deletion = send(HttpRequest.newBuilder(URI.create(hubBaseUrl
                + "/v2/namespaces/" + tag.namespace() + "/repositories/" + tag.repository()
                + "/tags/" + tag.tag() + "/"))
                .header("Authorization", "Bearer " + token).DELETE());
        if (deletion.statusCode() == 404) {
            log.info("Docker Hub tag already absent");
            return;
        }
        requireSuccess(deletion.statusCode(), "tag deletion");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try {
            return http.send(request.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    private void requireSuccess(int status, String operation) {
        if (status < 200 || status >= 300) {
            log.warn("Docker Hub {} failed HTTP status={}; verify delete permission and reconcile cleanup", operation, status);
            throw new IllegalStateException("Docker Hub operation failed HTTP status=" + status);
        }
    }
}
