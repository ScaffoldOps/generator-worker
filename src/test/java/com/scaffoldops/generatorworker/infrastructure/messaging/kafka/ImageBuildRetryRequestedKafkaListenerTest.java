package com.scaffoldops.generatorworker.infrastructure.messaging.kafka;
import com.scaffoldops.generatorworker.domain.event.ImageBuildRetryRequestedEvent;
import com.scaffoldops.generatorworker.domain.model.*;
import com.scaffoldops.generatorworker.application.service.ProcessGenerationRequestService;
import com.scaffoldops.generatorworker.application.port.out.*;
import com.scaffoldops.generatorworker.infrastructure.artifact.RecoveryArtifactLoader;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ImageBuildRetryRequestedKafkaListenerTest {
 private final ArtifactPublisher publisher=mock(ArtifactPublisher.class);
 private final ProjectGenerationPort generator=mock(ProjectGenerationPort.class);
 private final ImageBuilderPort builder=mock(ImageBuilderPort.class);
 private final GenerationLifecyclePort lifecycle=mock(GenerationLifecyclePort.class);
 private final RecoveryArtifactLoader loader=mock(RecoveryArtifactLoader.class);
 private final ProcessGenerationRequestService service=new ProcessGenerationRequestService(publisher,lifecycle,builder,generator);
 private final ImageBuildRetryRequestedKafkaListener listener=new ImageBuildRetryRequestedKafkaListener(service,loader,lifecycle);
 private final ImageBuildRetryRequestedEvent event=new ImageBuildRetryRequestedEvent(UUID.randomUUID(),"billing","spring",true,true,false,false,
  "KUBERNETES","s3://bucket/project.zip",3,"IMAGE_PUSH");
 @Test void consumesRetryAndBuildsExistingArtifactWithoutRegeneration() throws Exception {
  when(loader.load(event.artifactRef())).thenReturn(Path.of("/tmp/recovery"));
  when(builder.build(any())).thenReturn("docker.io/service:tag");
  listener.onMessage(event);
  verify(builder).build(argThat(a -> a.requestId().equals(event.generationRequestId()) && a.artifactReference().equals("file:///tmp/recovery")));
  verify(lifecycle).updateStatus(argThat(u -> u.status().equals("GENERATED") && u.artifactRef().equals(event.artifactRef())
   && u.imageRef().equals("docker.io/service:tag") && u.retryCount()==3));
  verifyNoInteractions(publisher,generator);verify(loader).remove(Path.of("/tmp/recovery"));
 }
 @Test void reportsBuildFailureWithPreservedArtifactAndAttempt() throws Exception {
  when(loader.load(event.artifactRef())).thenReturn(Path.of("/tmp/recovery"));
  when(builder.build(any())).thenThrow(new ImageGenerationException("IMAGE_PUSH","push unavailable",null,true));
  listener.onMessage(event);
  verify(builder,times(3)).build(any());
  verify(lifecycle).updateStatus(argThat(u -> u.status().equals("GENERATION_FAILED") && u.artifactRef().equals(event.artifactRef())
   && u.imageRef()==null && u.failureStage().equals("IMAGE_PUSH") && u.retryCount()==3));
  verifyNoInteractions(publisher,generator);
 }
 @Test void reportsDownloadFailureWithoutBuilding() throws Exception {
  when(loader.load(event.artifactRef())).thenThrow(new java.io.IOException("missing object"));
  listener.onMessage(event);
  verify(lifecycle).updateStatus(argThat(u -> u.status().equals("GENERATION_FAILED") && u.artifactRef().equals(event.artifactRef())
   && u.message().contains("missing object") && u.imageRef()==null && u.retryCount()==3));
  verifyNoInteractions(builder,publisher,generator);
 }
 @Test void propagatesCallbackFailuresToKafka() throws Exception {
  when(loader.load(event.artifactRef())).thenReturn(Path.of("/tmp/recovery"));
  when(builder.build(any())).thenReturn("docker.io/service:tag");
  doThrow(new IllegalStateException("API offline")).when(lifecycle).updateStatus(any());
  assertThatThrownBy(()->listener.onMessage(event)).hasMessage("API offline");
 }
 @Test void rejectsInvalidEvent() {
  assertThatThrownBy(()->listener.onMessage(null)).isInstanceOf(IllegalArgumentException.class);
  verifyNoInteractions(builder,loader,lifecycle);
 }
}
