package com.scaffoldops.generatorworker.infrastructure.artifact;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.file.*;
import java.io.*;
import java.util.zip.ZipInputStream;

@Component
public class RecoveryArtifactLoader {
 private final ObjectProvider<MinioClient> clients;
 public RecoveryArtifactLoader(ObjectProvider<MinioClient> clients) { this.clients=clients; }
 public Path load(String reference) throws Exception {
  URI uri=URI.create(reference);
  if("file".equals(uri.getScheme())) {
   Path path=Path.of(uri);
   if(!Files.isDirectory(path)) throw new IOException("Artifact directory missing");
   return path;
  }
  if(!"s3".equals(uri.getScheme()) || uri.getHost()==null || uri.getPath().length()<2)
   throw new IOException("Unsupported artifact reference");
  Path directory=Files.createTempDirectory("image-recovery-");
  try(var input=clients.getObject().getObject(GetObjectArgs.builder().bucket(uri.getHost()).object(uri.getPath().substring(1)).build());
      var zip=new ZipInputStream(input)) {
   long total=0; int entries=0;
   for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
    if(++entries>100000) throw new IOException("Artifact has too many entries");
    Path target=directory.resolve(entry.getName()).normalize();
    if(!target.startsWith(directory)) throw new IOException("Unsafe ZIP entry");
    if(entry.isDirectory()) Files.createDirectories(target);
    else {
     Files.createDirectories(target.getParent());
     try(var output=Files.newOutputStream(target)) {
      byte[] buffer=new byte[8192]; int n;
      while((n=zip.read(buffer))!=-1) {
       total+=n; if(total>1024L*1024*1024) throw new IOException("Artifact exceeds extraction limit");
       output.write(buffer,0,n);
      }
     }
    }
   }
   if(!Files.isRegularFile(directory.resolve("Dockerfile"))) throw new IOException("Artifact Dockerfile missing");
   return directory;
  } catch(Exception ex) { remove(directory); throw ex; }
 }
 public void remove(Path directory) throws IOException {
  try(var paths=Files.walk(directory)) {
   for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
  }
 }
}
