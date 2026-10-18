FROM docker:cli AS docker-cli

FROM eclipse-temurin:17-jdk

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker

WORKDIR /app

COPY target/generator-worker.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
