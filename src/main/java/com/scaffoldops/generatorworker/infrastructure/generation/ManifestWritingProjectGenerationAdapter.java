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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ManifestWritingProjectGenerationAdapter implements ProjectGenerationPort {

    private static final Logger log = LoggerFactory.getLogger(ManifestWritingProjectGenerationAdapter.class);
    private static final Pattern INVALID_SERVICE_NAME_CHARACTERS = Pattern.compile("[^a-z0-9-]");
    private static final Pattern INVALID_PACKAGE_CHARACTERS = Pattern.compile("[^a-z0-9]");

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

            TemplateVariables variables = templateVariables(request);
            Path projectDirectory = manifestOutputDirectory.resolve(
                    variables.serviceName() + "-" + variables.requestId()
            );
            Path manifestPath = projectDirectory.resolve("generation-manifest.json");
            if (Files.exists(manifestPath)) {
                String existingReference = projectDirectory.toAbsolutePath().toUri().toString();
                log.info(
                        "Reusing existing project artifact requestId={} serviceName={} artifactReference={} workerService=generator-worker",
                        request.requestId(),
                        request.name(),
                        existingReference
                );
                return new GenerationArtifact(
                        request.requestId(),
                        "spring-boot-project",
                        existingReference,
                        variables.serviceName(),
                        variables.imageName(),
                        OffsetDateTime.ofInstant(Files.getLastModifiedTime(manifestPath).toInstant(), OffsetDateTime.now().getOffset())
                );
            }

            writeProject(projectDirectory, variables);
            objectMapper.writeValue(manifestPath.toFile(), manifestPayload(request, variables));

            String artifactReference = projectDirectory.toAbsolutePath().toUri().toString();
            log.info(
                    "Generated Spring Boot project artifact requestId={} serviceName={} artifactReference={} workerService=generator-worker",
                    request.requestId(),
                    request.name(),
                    artifactReference
            );

            return new GenerationArtifact(
                    request.requestId(),
                    "spring-boot-project",
                    artifactReference,
                    variables.serviceName(),
                    variables.imageName(),
                    OffsetDateTime.now()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to generate project for requestId=" + request.requestId(), exception);
        }
    }

    private void writeProject(Path projectDirectory, TemplateVariables variables) throws IOException {
        Path javaDirectory = projectDirectory
                .resolve("src/main/java")
                .resolve(variables.packageName().replace('.', '/'));

        Files.createDirectories(javaDirectory);
        Files.createDirectories(projectDirectory.resolve("k8s"));

        Files.writeString(projectDirectory.resolve("pom.xml"), pomXml(variables));
        Files.writeString(javaDirectory.resolve("HelloApplication.java"), helloApplication(variables));
        Files.writeString(javaDirectory.resolve("HelloController.java"), helloController(variables));
        Files.writeString(projectDirectory.resolve("Dockerfile"), dockerfile());
        Files.writeString(projectDirectory.resolve("k8s/deployment.yaml"), kubernetesDeployment(variables));
        Files.writeString(projectDirectory.resolve("k8s/service.yaml"), kubernetesService(variables));
    }

    private TemplateVariables templateVariables(GenerationRequest request) {
        String serviceName = INVALID_SERVICE_NAME_CHARACTERS
                .matcher(request.name().toLowerCase(Locale.ROOT))
                .replaceAll("-");
        serviceName = serviceName.replaceAll("-+", "-").replaceAll("(^-|-$)", "");
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("service name must contain at least one alphanumeric character");
        }

        String packageSegment = INVALID_PACKAGE_CHARACTERS.matcher(serviceName).replaceAll("");
        if (Character.isDigit(packageSegment.charAt(0))) {
            packageSegment = "service" + packageSegment;
        }

        String packageName = "com.scaffoldops.generated." + packageSegment;
        String imageName = "scaffoldops/" + serviceName + ":" + request.requestId();
        return new TemplateVariables(serviceName, request.requestId().toString(), packageName, imageName);
    }

    private String pomXml(TemplateVariables variables) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.4.6</version>
                        <relativePath/>
                    </parent>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <name>%s</name>
                    <description>Generated Hello World microservice for request %s</description>

                    <properties>
                        <java.version>17</java.version>
                        <generated.request-id>%s</generated.request-id>
                        <generated.image-name>%s</generated.image-name>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>

                    <build>
                        <finalName>app</finalName>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(
                variables.packageName(),
                variables.serviceName(),
                variables.serviceName(),
                variables.requestId(),
                variables.requestId(),
                variables.imageName()
        );
    }

    private String helloApplication(TemplateVariables variables) {
        return """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class HelloApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(HelloApplication.class, args);
                    }
                }
                """.formatted(variables.packageName());
    }

    private String helloController(TemplateVariables variables) {
        return """
                package %s;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class HelloController {

                    @GetMapping("/hello")
                    public String hello() {
                        return "Hello from %s";
                    }
                }
                """.formatted(variables.packageName(), variables.serviceName());
    }

    private String dockerfile() {
        return """
                FROM maven:3.9.9-eclipse-temurin-17 AS build

                WORKDIR /workspace
                COPY pom.xml .
                COPY src src
                RUN mvn --batch-mode --no-transfer-progress package -DskipTests

                FROM eclipse-temurin:17-jre
                WORKDIR /app
                COPY --from=build /workspace/target/app.jar app.jar

                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """;
    }

    private String kubernetesDeployment(TemplateVariables variables) {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: %s
                  labels:
                    app: %s
                    scaffoldops.io/request-id: "%s"
                spec:
                  replicas: 1
                  selector:
                    matchLabels:
                      app: %s
                  template:
                    metadata:
                      labels:
                        app: %s
                    spec:
                      containers:
                        - name: %s
                          image: %s
                          ports:
                            - containerPort: 8080
                """.formatted(
                variables.serviceName(),
                variables.serviceName(),
                variables.requestId(),
                variables.serviceName(),
                variables.serviceName(),
                variables.serviceName(),
                variables.imageName()
        );
    }

    private String kubernetesService(TemplateVariables variables) {
        return """
                apiVersion: v1
                kind: Service
                metadata:
                  name: %s
                  labels:
                    app: %s
                    scaffoldops.io/request-id: "%s"
                spec:
                  selector:
                    app: %s
                  ports:
                    - name: http
                      port: 80
                      targetPort: 8080
                """.formatted(
                variables.serviceName(),
                variables.serviceName(),
                variables.requestId(),
                variables.serviceName()
        );
    }

    private Map<String, Object> manifestPayload(GenerationRequest request, TemplateVariables variables) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.requestId());
        payload.put("serviceName", variables.serviceName());
        payload.put("packageName", variables.packageName());
        payload.put("imageName", variables.imageName());
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

    private record TemplateVariables(
            String serviceName,
            String requestId,
            String packageName,
            String imageName
    ) {
    }
}
