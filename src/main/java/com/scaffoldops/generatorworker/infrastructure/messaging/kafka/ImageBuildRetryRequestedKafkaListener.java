package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;
import com.scaffoldops.generatorworker.domain.event.ImageBuildRetryRequestedEvent;
import com.scaffoldops.generatorworker.domain.model.*;
import com.scaffoldops.generatorworker.application.service.ProcessGenerationRequestService;
import com.scaffoldops.generatorworker.application.port.out.GenerationLifecyclePort;
import com.scaffoldops.generatorworker.infrastructure.artifact.RecoveryArtifactLoader;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.time.OffsetDateTime;

@Component
public class ImageBuildRetryRequestedKafkaListener {
 private final ProcessGenerationRequestService service;
 private final RecoveryArtifactLoader loader;
 private final GenerationLifecyclePort lifecycle;
 public ImageBuildRetryRequestedKafkaListener(ProcessGenerationRequestService service,RecoveryArtifactLoader loader,GenerationLifecyclePort lifecycle) {
  this.service=service; this.loader=loader; this.lifecycle=lifecycle;
 }
 @KafkaListener(topics="${app.kafka.topics.image-build-retry-requested:image-build-retry-requested}",
  groupId="${spring.kafka.consumer.group-id:generator-worker}",containerFactory="imageBuildRetryRequestedKafkaListenerContainerFactory")
 public void onMessage(ImageBuildRetryRequestedEvent event) {
  if(event==null || event.generationRequestId()==null || event.name()==null || event.name().isBlank()
   || event.artifactRef()==null || event.artifactRef().isBlank() || event.retryAttempt()<1
   || !("IMAGE_BUILD".equals(event.failureStage()) || "IMAGE_PUSH".equals(event.failureStage())))
   throw new IllegalArgumentException("Invalid image recovery event");
  Path directory;
  try { directory=loader.load(event.artifactRef()); }
  catch(Exception ex) {
   lifecycle.updateStatus(new GenerationLifecycleUpdate(event.generationRequestId(),"GENERATION_FAILED",
    "Could not load artifact for image recovery: "+ex.getMessage(),event.artifactRef(),null,"IMAGE_BUILD",event.retryAttempt()));
   return;
  }
  try {
   var now=OffsetDateTime.now();
   var request=new GenerationRequest(event.generationRequestId(),event.name(),event.template(),event.database(),event.restApi(),
    event.security(),event.messaging(),event.deploymentTarget(),"GENERATION_FAILED",now);
   var artifact=new GenerationArtifact(event.generationRequestId(),"project",directory.toUri().toString(),event.name(),event.name(),now);
   service.recover(request,artifact,event.artifactRef(),event.retryAttempt());
  } finally {
   if(event.artifactRef().startsWith("s3://")) {
    try { loader.remove(directory); }
    catch(Exception ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Recovery workspace cleanup failed",ex); }
   }
  }
 }
}
