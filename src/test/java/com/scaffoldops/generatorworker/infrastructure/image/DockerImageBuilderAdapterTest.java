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
                "printf '%s\\n' \"$@\" > \"" + argumentsFile + "\"\nexit 0"
        );
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(true, dockerCommand.toString());

        String imageRef = adapter.build(artifact(projectDirectory));

        assertThat(imageRef).isEqualTo("scaffoldops/billing-service:11111111-1111-1111-1111-111111111111");
        assertThat(Files.readAllLines(argumentsFile)).containsExactly(
                "build",
                "--tag",
                "scaffoldops/billing-service:11111111-1111-1111-1111-111111111111",
                projectDirectory.toString()
        );
    }

    @Test
    void shouldFailWhenDockerBuildReturnsNonZeroExitCode() throws Exception {
        Path projectDirectory = generatedProject();
        Path dockerCommand = executableScript("echo 'daemon unavailable'\nexit 17");
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(true, dockerCommand.toString());

        assertThatThrownBy(() -> adapter.build(artifact(projectDirectory)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("docker build failed")
                .hasMessageContaining("exitCode=17")
                .hasMessageContaining("daemon unavailable");
    }

    @Test
    void shouldSkipDockerBuildWhenDisabled() throws Exception {
        Path projectDirectory = generatedProject();
        Path argumentsFile = tempDir.resolve("docker-arguments-disabled.txt");
        Path dockerCommand = executableScript(
                "printf '%s\\n' \"$@\" > \"" + argumentsFile + "\"\nexit 0"
        );
        DockerImageBuilderAdapter adapter = new DockerImageBuilderAdapter(false, dockerCommand.toString());

        String imageRef = adapter.build(artifact(projectDirectory));

        assertThat(imageRef).isNull();
        assertThat(argumentsFile).doesNotExist();
    }

    private Path generatedProject() throws Exception {
        Path projectDirectory = tempDir.resolve("billing-service");
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("Dockerfile"), "FROM scratch\n");
        return projectDirectory;
    }

    private Path executableScript(String body) throws Exception {
        Path script = tempDir.resolve("docker-command-" + UUID.randomUUID() + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    private GenerationArtifact artifact(Path projectDirectory) {
        return new GenerationArtifact(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "spring-boot-project",
                projectDirectory.toUri().toString(),
                "billing-service",
                "scaffoldops/billing-service:11111111-1111-1111-1111-111111111111",
                OffsetDateTime.parse("2026-06-11T12:00:00Z")
        );
    }
}
