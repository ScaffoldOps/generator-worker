package com.scaffoldops.generatorworker.infrastructure.artifact;
import io.minio.*;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.io.*;
import java.util.zip.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class RecoveryArtifactLoaderTest {
 @TempDir Path directory;
 private final MinioClient client=mock(MinioClient.class);
 private final ObjectProvider<MinioClient> provider=mock(ObjectProvider.class);
 private RecoveryArtifactLoader loader() {when(provider.getObject()).thenReturn(client);return new RecoveryArtifactLoader(provider);}
 private void object(String entry,String content) throws Exception {
  var bytes=new ByteArrayOutputStream();
  try(var zip=new ZipOutputStream(bytes)) {
   zip.putNextEntry(new ZipEntry(entry));zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
  }
  when(client.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(new Headers.Builder().build(),"bucket",null,
   "project.zip",new ByteArrayInputStream(bytes.toByteArray())));
 }
 @Test void downloadsAndExtractsProjectZip() throws Exception {
  object("Dockerfile","FROM scratch\n");
  var loader=loader();Path project=loader.load("s3://bucket/project.zip");
  try {assertThat(project.resolve("Dockerfile")).hasContent("FROM scratch\n");
   verify(client).getObject(argThat(args -> args.bucket().equals("bucket") && args.object().equals("project.zip")));
  } finally {loader.remove(project);}
  assertThat(project).doesNotExist();
 }
 @Test void rejectsZipTraversal() throws Exception {
  object("../escape","unsafe");
  assertThatThrownBy(()->loader().load("s3://bucket/project.zip")).hasMessageContaining("Unsafe ZIP entry");
 }
 @Test void requiresDockerfile() throws Exception {
  object("pom.xml","project");
  assertThatThrownBy(()->loader().load("s3://bucket/project.zip")).hasMessageContaining("Dockerfile missing");
 }
 @Test void reusesFilesystemArtifactDirectory() throws Exception {
  assertThat(loader().load(directory.toUri().toString())).isEqualTo(directory);verifyNoInteractions(client);
 }
}
