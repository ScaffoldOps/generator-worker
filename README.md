# Generator Worker

## Purpose
`generator-worker` is the ScaffoldOps microservice responsible for asynchronously consuming generation jobs after they are accepted by `generator-api`.

## What This Service Does
- Consumes generation-requested events from Kafka
- Orchestrates placeholder generation job handling
- Emits placeholder lifecycle status updates for generation stages
- Exposes actuator health endpoints for platform operations

## What This Service Does Not Do Yet
- Does not expose the public request API
- Does not implement the full generation engine
- Does not persist generation request state
- Does not implement deployment-worker responsibilities
- Does not perform real lifecycle update calls yet

## Architecture
Hexagonal (ports and adapters):
- `domain`: worker job and event models
- `application`: worker use case and outbound ports
- `infrastructure`: Kafka consumer, lifecycle adapter, generation adapter, configuration

Dependency direction:
`infrastructure -> application -> domain`

There is intentionally no public API layer in this service shell because this service is a background worker, not the synchronous platform entrypoint.

## Main Tech Stack
- Java 17
- Spring Boot 3
- Spring Kafka
- Spring Web and Actuator for health/runtime operations
- Maven

## Run
Start the worker locally with the `local` Spring profile:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Useful environment variables:
- `KAFKA_BOOTSTRAP_SERVERS`
- `KAFKA_CONSUMER_GROUP`
- `GENERATION_REQUESTED_TOPIC`
- `GENERATOR_API_BASE_URL`
- `SERVER_PORT`

## Test
```bash
./mvnw test
```

## Runtime Endpoints
- Actuator health: `/actuator/health`
- Liveness probe: `/actuator/health/liveness`
- Readiness probe: `/actuator/health/readiness`

## Docker
```bash
./mvnw clean package -DskipTests
docker build -f Dockerfile -t scaffoldops/generator-worker:latest .
```

## Kubernetes
Deployment assets live under `k8s/`:
- `k8s/deployment`

This repository owns the worker application manifests and runtime configuration only.

## Notes
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current worker shell boundaries and the next implementation phase.
