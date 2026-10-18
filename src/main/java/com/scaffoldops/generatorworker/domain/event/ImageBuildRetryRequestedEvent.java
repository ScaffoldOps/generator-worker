package com.scaffoldops.generatorworker.domain.event;
import java.util.UUID;
public record ImageBuildRetryRequestedEvent(UUID generationRequestId, String name, String template,
 boolean database, boolean restApi, boolean security, boolean messaging, String deploymentTarget,
 String artifactRef, int retryAttempt, String failureStage) {}
