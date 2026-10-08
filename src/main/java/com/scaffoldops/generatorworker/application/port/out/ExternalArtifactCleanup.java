package com.scaffoldops.generatorworker.application.port.out;

public interface ExternalArtifactCleanup {
    void deleteArtifact(String artifactRef) throws Exception;
    void deleteImage(String imageRef) throws Exception;
}
