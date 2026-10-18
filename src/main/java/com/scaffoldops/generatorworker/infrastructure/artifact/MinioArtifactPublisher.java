package com.scaffoldops.generatorworker.infrastructure.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaffoldops.generatorworker.application.port.out.ArtifactPublisher;
import com.scaffoldops.generatorworker.domain.model.GenerationArtifact;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@ConditionalOnProperty(name = "app.artifact.storage-type", havingValue = "minio")
public class MinioArtifactPublisher implements ArtifactPublisher {
    private final MinioClient client;
    private final ObjectMapper mapper;
    private final String bucket;

    public MinioArtifactPublisher(MinioClient client, ObjectMapper mapper,
                                  @Value("${app.artifact.minio.bucket:scaffoldops-artifacts}") String bucket) {
        this.client = client;
        this.mapper = mapper;
        this.bucket = bucket;
    }

    @Override
    public String publish(GenerationArtifact artifact, String intendedImageRef) {
        String object = artifact.requestId() + "/project.zip";
        String reference = "s3://" + bucket + "/" + object;
        try {
            Path directory = ArtifactManifestWriter.finalizeManifest(mapper, artifact, reference, intendedImageRef);
            Path zip = Files.createTempFile("generator-artifact-", ".zip");
            try {
                try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip));
                     var paths = Files.walk(directory)) {
                    for (Path file : paths.sorted().toList()) {
                        if (Files.isSymbolicLink(file)) {
                            throw new IllegalStateException("artifact contains symbolic link: " + file);
                        }
                        if (Files.isRegularFile(file)) {
                            output.putNextEntry(new ZipEntry(directory.relativize(file).toString().replace('\\', '/')));
                            Files.copy(file, output);
                            output.closeEntry();
                        }
                    }
                }
                client.uploadObject(UploadObjectArgs.builder().bucket(bucket).object(object)
                        .filename(zip.toString()).contentType("application/zip").build());
            } finally {
                Files.deleteIfExists(zip);
            }
            return reference;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to publish artifact for requestId=" + artifact.requestId(), exception);
        }
    }
}
