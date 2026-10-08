package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

final class ArtifactManifestWriter {
    private ArtifactManifestWriter() { }

    static Path finalizeManifest(ObjectMapper mapper, GenerationArtifact artifact,
                                 String artifactRef, String imageRef) throws IOException {
        Path directory = Path.of(URI.create(artifact.artifactReference()));
        Path manifest = directory.resolve("generation-manifest.json");
        Map<String, Object> payload = mapper.readValue(manifest.toFile(), new TypeReference<>() { });
        payload.put("artifactRef", artifactRef);
        payload.put("imageRef", imageRef);
        mapper.writeValue(manifest.toFile(), payload);
        return directory;
    }
}
