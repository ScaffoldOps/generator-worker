package com.scaffoldops.generatorworker.infrastructure.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.application.port.out.ProjectGenerationPort;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import com.scaffoldops.generatorworker.domain.model.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ManifestWritingProjectGenerationAdapter implements ProjectGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(ManifestWritingProjectGenerationAdapter.class);

    private final ObjectMapper objectMapper;
    private final Path manifestOutputDirectory;

    public ManifestWritingProjectGenerationAdapter(
            ObjectMapper objectMapper,
            @Value("${app.generation.manifest-output-dir:${java.io.tmpdir}/generator-worker/manifests}")
            String manifestOutputDirectory
    ) {
        this.objectMapper = objectMapper;
        this.manifestOutputDirectory = Path.of(manifestOutputDirectory);
    }

    @Override
    public GenerationArtifact generate(GenerationRequest request) {
        try {
            Files.createDirectories(manifestOutputDirectory);

            Path manifestPath = manifestOutputDirectory.resolve("manifest-" + request.requestId() + ".json");
            if (Files.exists(manifestPath)) {
                String existingReference = manifestPath.toAbsolutePath().toUri().toString();
                log.info(
                        "Reusing existing manifest artifact requestId={} serviceName={} artifactReference={} workerService=generator-worker",
                        request.requestId(),
                        request.name(),
                        existingReference
                );
                return new GenerationArtifact(
                        request.requestId(),
                        "generation-manifest",
                        existingReference,
                        OffsetDateTime.ofInstant(Files.getLastModifiedTime(manifestPath).toInstant(), OffsetDateTime.now().getOffset())
                );
            }

            objectMapper.writeValue(manifestPath.toFile(), manifestPayload(request));

            String artifactReference = manifestPath.toAbsolutePath().toUri().toString();
            log.info(
                    "Generated manifest artifact requestId={} serviceName={} artifactReference={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    artifactReference
            );

            return new GenerationArtifact(
                    request.requestId(),
                    "generation-manifest",
                    artifactReference,
                    OffsetDateTime.now()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write generation manifest for requestId=" + request.requestId(), exception);
        }
    }

    private Map<String, Object> manifestPayload(GenerationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.requestId());
        payload.put("name", request.name());
        payload.put("template", request.template());
        payload.put("database", request.database());
        payload.put("restApi", request.restApi());
        payload.put("security", request.security());
        payload.put("messaging", request.messaging());
        payload.put("deploymentTarget", request.deploymentTarget());
        payload.put("status", request.status());
        payload.put("createdAt", request.createdAt().toString());
        payload.put("manifestCreatedAt", OffsetDateTime.now().toString());
        return payload;
    }
}
