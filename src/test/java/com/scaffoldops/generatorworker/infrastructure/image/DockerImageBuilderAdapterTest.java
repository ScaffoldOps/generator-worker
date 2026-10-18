package com.scaffoldops.generatorworker.infrastructure.image;

import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerImageBuilderAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunDockerBuildWithGeneratedProjectAndImageTag() throws Exception {
        Path projectDirectory = generatedProject();
        Path argumentsFile = tempDir.resolve("docker-arguments.txt");
        Path dockerCommand = executableScript(
                "printf '%s\\n' \"$@\" >> \"" + argumentsFile + "\"\nexit 0"
        );
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(true, dockerCommand.toString(), true, "registry.local", "scaffoldops-generated", "test-user", "test-token");

        String imageRef = adapter.build(artifact(projectDirectory));

        assertThat(imageRef).isEqualTo("registry.local/scaffoldops-generated:billing-service-11111111-1111-1111-1111-111111111111");
        assertThat(Files.readAllLines(argumentsFile)).containsExactly(
                "build",
                "--tag",
                "registry.local/scaffoldops-generated:billing-service-11111111-1111-1111-1111-111111111111",
                projectDirectory.toString(),
                "login", "registry.local", "--username", "test-user", "--password-stdin",
                "push",
                "registry.local/scaffoldops-generated:billing-service-11111111-1111-1111-1111-111111111111"
        );
    }

    @Test
    void shouldFailWhenDockerBuildReturnsNonZeroExitCode() throws Exception {
        Path projectDirectory = generatedProject();
        Path dockerCommand = executableScript("echo 'daemon unavailable'\nexit 17");
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(true, dockerCommand.toString(), true, "registry.local", "scaffoldops-generated", "test-user", "test-token");

        assertThatThrownBy(() -> adapter.build(artifact(projectDirectory)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker build failed")
                .hasMessageContaining("exitCode=17")
                .hasMessageContaining("daemon unavailable");
    }

    @Test
    void shouldFailWhenDockerBuildDisabled() throws Exception {
        Path projectDirectory = generatedProject();
        Path argumentsFile = tempDir.resolve("docker-arguments-disabled.txt");
        Path dockerCommand = executableScript(
                "printf '%s\\n' \"$@\" >> \"" + argumentsFile + "\"\nexit 0"
        );
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(false, dockerCommand.toString());

        assertThatThrownBy(() -> adapter.build(artifact(projectDirectory)))
                .hasMessage("Docker image build is disabled; cannot complete generation");
        assertThat(argumentsFile).doesNotExist();
    }

    @Test
    void shouldBuildAndPushTheSameRegistryReference() throws Exception {
        Path project = generatedProject();
        Path arguments = tempDir.resolve("push-arguments.txt");
        Path command = executableScript("printf '%s\\n' \"$@\" >> \"" + arguments + "\"\nexit 0");
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(true, command.toString(),
                true, "registry.local", "scaffoldops-generated", "test-user", "test-token");
        String expected = "registry.local/scaffoldops-generated:billing-service-11111111-1111-1111-1111-111111111111";
        assertThat(adapter.build(artifact(project))).isEqualTo(expected);
        assertThat(Files.readAllLines(arguments)).containsExactly("build", "--tag", expected,
                project.toString(), "login", "registry.local", "--username", "test-user", "--password-stdin", "push", expected);
    }

    @Test
    void shouldFailWhenPushFails() throws Exception {
        Path command = executableScript("if [ \"$1\" = push ]; then echo 'push rejected'; exit 19; fi\nexit 0");
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(true, command.toString(),
                true, "registry.local", "scaffoldops-generated", "test-user", "test-token");
        assertThatThrownBy(() -> adapter.build(artifact(generatedProject())))
                .hasMessageContaining("docker push failed").hasMessageContaining("exitCode=19");
    }

    @Test
    void shouldRejectPushWithoutBuildOrRegistry() {
        assertThatThrownBy(() -> new DockerImageBuilderAdapter(true, "docker", true, "", "generated"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFailBeforeInvokingDockerWhenPushDisabled() throws Exception {
        Path arguments = tempDir.resolve("disabled-push.txt");
        Path command = executableScript("echo invoked > \"" + arguments + "\"");
        var adapter = new DockerImageBuilderAdapter(true, command.toString(), false, "registry.local", "generated");
        assertThatThrownBy(() -> adapter.build(artifact(generatedProject())))
                .hasMessage("Docker image push is disabled; cannot complete generation");
        assertThat(arguments).doesNotExist();
    }

    @Test
    void shouldLoginUsingStdinAndPushExactDockerHubReference() throws Exception {
        Path project = generatedProject();
        Path arguments = tempDir.resolve("hub-arguments.txt");
        Path loginInput = tempDir.resolve("login-input.txt");
        Path command = tempDir.resolve("docker-hub.sh");
        Files.writeString(command, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" >> \"" + arguments + "\"\n"
                + "if [ \"$1\" = login ]; then cat > \"" + loginInput + "\"; fi\nexit 0\n");
        assertThat(command.toFile().setExecutable(true)).isTrue();
        var adapter = new DockerImageBuilderAdapter(true, command.toString(), true,
                "docker.io", "victodomvar/scaffoldops-generated", "victodomvar", "example-access-token");
        String expected = "docker.io/victodomvar/scaffoldops-generated:billing-service-11111111-1111-1111-1111-111111111111";
        assertThat(adapter.build(artifact(project))).isEqualTo(expected);
        assertThat(Files.readAllLines(arguments)).containsExactly("build", "--tag", expected,
                project.toString(), "login", "docker.io", "--username", "victodomvar", "--password-stdin",
                "push", expected);
        assertThat(Files.readString(loginInput)).isEqualTo("example-access-token\n");
        assertThat(Files.readString(arguments)).doesNotContain("example-access-token");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"username", "password"})
    void shouldFailClearlyWithMissingCredentialsBeforeInvokingDocker(String missing) throws Exception {
        Path invoked = tempDir.resolve("missing-credentials.txt");
        Path command = executableScript("echo invoked > \"" + invoked + "\"");
        var adapter = new DockerImageBuilderAdapter(true, command.toString(), true, "docker.io",
                "victodomvar/scaffoldops-generated", missing.equals("username") ? "" : "victodomvar",
                missing.equals("password") ? "" : "example-access-token");
        assertThatThrownBy(() -> adapter.build(artifact(generatedProject())))
                .isInstanceOf(com.scaffoldops.generatorworker.domain.model.ImageGenerationException.class)
                .hasMessageContaining("DOCKER_USERNAME and DOCKER_PASSWORD")
                .satisfies(exception -> {
                    var failure = (com.scaffoldops.generatorworker.domain.model.ImageGenerationException) exception;
                    assertThat(failure.failureStage()).isEqualTo("IMAGE_PUSH");
                    assertThat(failure.retryable()).isFalse();
                });
        assertThat(invoked).doesNotExist();
    }

    @Test
    void shouldReportLoginFailureWithoutTokenOrLoginOutputAndWithoutPushing() throws Exception {
        Path arguments = tempDir.resolve("failed-login.txt");
        Path command = executableScript("printf '%s\\n' \"$@\" >> \"" + arguments + "\"\n"
                + "if [ \"$1\" = login ]; then echo 'example-access-token'; exit 23; fi\nexit 0");
        var adapter = new DockerImageBuilderAdapter(true, command.toString(), true,
                "docker.io", "victodomvar/scaffoldops-generated", "victodomvar", "example-access-token");
        assertThatThrownBy(() -> adapter.build(artifact(generatedProject())))
                .hasMessageContaining("docker login failed")
                .hasMessageContaining("exitCode=23")
                .hasMessageNotContaining("example-access-token")
                .satisfies(exception -> assertThat(
                        ((com.scaffoldops.generatorworker.domain.model.ImageGenerationException) exception).failureStage())
                        .isEqualTo("IMAGE_PUSH"));
        assertThat(Files.readAllLines(arguments)).doesNotContain("push");
    }

    @Test
    void shouldRedactTokenFromFailedPushOutput() throws Exception {
        Path command = executableScript("if [ \"$1\" = push ]; then echo 'example-access-token'; exit 19; fi\nexit 0");
        var adapter = new DockerImageBuilderAdapter(true, command.toString(), true,
                "docker.io", "victodomvar/scaffoldops-generated", "victodomvar", "example-access-token");
        assertThatThrownBy(() -> adapter.build(artifact(generatedProject())))
                .hasMessageContaining("[REDACTED]").hasMessageNotContaining("example-access-token");
    }

    private Path generatedProject() throws Exception {
        Path projectDirectory = tempDir.resolve("billing-service");
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("Dockerfile"), "FROM scratch\n");
        return projectDirectory;
    }

    private Path executableScript(String body) throws Exception {
        Path script = tempDir.resolve("docker-command-" + UUID.randomUUID() + ".sh");
        Files.writeString(script, "#!/bin/sh\nif [ \"$1\" = login ]; then cat >/dev/null; fi\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    private GenerationArtifact artifact(Path projectDirectory) {
        return new GenerationArtifact(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "spring-boot-project",
                projectDirectory.toUri().toString(),
                "billing-service",
                "registry.local/scaffoldops-generated:billing-service-11111111-1111-1111-1111-111111111111",
                OffsetDateTime.parse("2026-06-11T12:00:00Z")
        );
    }
}
