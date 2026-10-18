package com.scaffoldops.generatorworker.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMinikubeManifestTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldExplicitlyRemoveAlternateHandlersFromEveryProbeDuringApply() throws Exception {
        Map<String, Object> deployment;
        try (var input = Files.newInputStream(Path.of("k8s/deployment/generator-worker-deployment.yaml"))) {
            deployment = new Yaml().load(input);
        }
        var spec = (Map<String, Object>) deployment.get("spec");
        var template = (Map<String, Object>) spec.get("template");
        var pod = (Map<String, Object>) template.get("spec");
        var container = ((List<Map<String, Object>>) pod.get("containers")).get(0);
        for (String name : List.of("startupProbe", "readinessProbe", "livenessProbe")) {
            var probe = (Map<String, Object>) container.get(name);
            assertThat(probe).as(name).containsKeys("httpGet", "tcpSocket", "exec", "grpc");
            assertThat(probe.get("httpGet")).isNotNull();
            for (String alternate : List.of("tcpSocket", "exec", "grpc")) {
                // Omission preserves manually added handlers absent from last-applied configuration.
                assertThat(probe).as(name).containsEntry(alternate, null);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMountNodeDockerSocketAndUseSecretCredentialsInSharedLocalMinikubeManifest() throws Exception {
        Map<String, Object> deployment;
        try (var input = Files.newInputStream(Path.of("k8s/deployment/generator-worker-deployment.yaml"))) {
            deployment = new Yaml().load(input);
        }
        assertThat((Map<String, Object>) deployment.get("metadata")).doesNotContainKey("namespace");
        var spec = (Map<String, Object>) deployment.get("spec");
        var template = (Map<String, Object>) spec.get("template");
        var pod = (Map<String, Object>) template.get("spec");
        assertThat((List<Map<String, Object>>) pod.get("imagePullSecrets"))
                .containsExactly(Map.of("name", "docker-hub-pull-secret"));
        var volumes = (List<Map<String, Object>>) pod.get("volumes");
        assertThat(volumes).anySatisfy(volume -> {
            assertThat(volume.get("name")).isEqualTo("docker-socket");
            assertThat((Map<String, Object>) volume.get("hostPath"))
                    .containsEntry("path", "/var/run/docker.sock").containsEntry("type", "Socket");
        });
        var container = ((List<Map<String, Object>>) pod.get("containers")).get(0);
        assertThat((List<Map<String, Object>>) container.get("volumeMounts")).anySatisfy(mount ->
                assertThat(mount).containsEntry("name", "docker-socket").containsEntry("mountPath", "/var/run/docker.sock"));
        var env = (List<Map<String, Object>>) container.get("env");
        assertThat(env).anySatisfy(value -> assertThat(value)
                .containsEntry("name", "GENERATOR_API_BASE_URL")
                .containsEntry("value", "http://generator-api-service/api/generator/v1"));
        var startup = (Map<String, Object>) container.get("startupProbe");
        assertThat(startup).containsEntry("failureThreshold", 60).containsEntry("periodSeconds", 5);
        assertThat((Map<String, Object>) startup.get("httpGet"))
                .containsEntry("path", "/actuator/health/liveness").containsEntry("port", 8080);
        for (String flag : List.of("GENERATOR_DOCKER_BUILD_ENABLED", "GENERATOR_DOCKER_PUSH_ENABLED")) {
            assertThat(env).anySatisfy(value -> assertThat(value).containsEntry("name", flag).containsEntry("value", "true"));
        }
        assertThat(env).anySatisfy(value -> assertThat(value).containsEntry("name", "GENERATOR_IMAGE_REGISTRY").containsEntry("value", "docker.io"));
        assertThat(env).anySatisfy(value -> assertThat(value).containsEntry("name", "GENERATOR_IMAGE_REPOSITORY_PREFIX").containsEntry("value", "victodomvar/scaffoldops-generated"));
        assertThat(env).anySatisfy(value -> assertThat(value).containsEntry("name", "DOCKER_COMMAND").containsEntry("value", "docker"));
        for (String credential : List.of("DOCKER_USERNAME", "DOCKER_PASSWORD")) {
            assertThat(env).anySatisfy(value -> {
                assertThat(value.get("name")).isEqualTo(credential);
                assertThat(value).doesNotContainKey("value");
                var source = (Map<String, Object>) value.get("valueFrom");
                assertThat((Map<String, Object>) source.get("secretKeyRef"))
                        .containsEntry("name", "docker-hub-credentials").containsEntry("key", credential);
            });
        }
        assertThat(Files.readString(Path.of("k8s/deployment/kustomization.yaml")))
                .doesNotContain("docker-hub-credentials.yaml");
    }
}
