# Generator Worker

`generator-worker` is the ScaffoldOps background worker responsible for consuming generation jobs from Kafka and driving the generation lifecycle asynchronously.

## Current behavior

The service currently implements the worker shell and placeholder execution flow:

- Consumes `GenerationRequestedEvent` messages from Kafka
- Maps the event into an internal `GenerationJob`
- Emits lifecycle updates through the lifecycle port with these status transitions:
  - `GENERATING` when the worker accepts the job
  - `GENERATED` when the placeholder generation flow completes
  - `FAILED` if the generation adapter throws
- Logs lifecycle updates instead of calling a real downstream API
- Logs placeholder generation execution instead of producing project artifacts
- Exposes Spring Boot actuator health and info endpoints

## Current limitations

- No real project generation engine yet
- No outbound HTTP integration for lifecycle updates yet
- No persistence layer in this service
- No public business API; this is a worker-only service
- No retry, dead-letter, or idempotency workflow beyond Kafka consumer defaults

## Architecture

The codebase follows a ports-and-adapters layout:

- `domain`: event and model records
- `application`: use case plus inbound and outbound ports
- `infrastructure`: Kafka consumer, adapter implementations, and configuration

Dependency direction:

`infrastructure -> application -> domain`

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the detailed flow and extension points.

## Event contract

The Kafka consumer expects JSON messages compatible with `GenerationRequestedEvent`:

```json
{
  "requestId": "2f5c3a0d-5a2e-4c6f-85d9-7e8d8ef4b4f5",
  "name": "billing-service",
  "template": "spring-boot-hexagonal",
  "database": true,
  "restApi": true,
  "security": true,
  "messaging": false,
  "deploymentTarget": "kubernetes",
  "status": "REQUESTED",
  "createdAt": "2026-03-16T10:15:30Z"
}
```

Notes:

- `status` is currently carried on the inbound event model but is not used by the worker flow
- The consumer uses Spring Kafka `JsonDeserializer` with `GenerationRequestedEvent` as the default value type
- The listener subscribes to `app.kafka.topics.generation-requested`

## Runtime configuration

Core configuration lives in [`src/main/resources/application.yml`](/home/victor/workspace/ScaffoldOps/generator-worker/src/main/resources/application.yml).

Main environment variables:

- `SERVER_PORT`: HTTP port for actuator endpoints, default `8080`
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka bootstrap servers, default `localhost:9092`
- `KAFKA_CONSUMER_GROUP`: Kafka consumer group, default `generator-worker`
- `GENERATION_REQUESTED_TOPIC`: inbound topic name, default `generation-requested`
- `GENERATOR_API_BASE_URL`: placeholder lifecycle target base URL, default `http://generator-api-service`

Profile overrides:

- `local`: points the lifecycle base URL to `http://localhost:8081`
- `dev`: points Kafka and lifecycle integration to `scaffoldops-dev` cluster service DNS
- `pre`: points Kafka and lifecycle integration to `scaffoldops-pre` cluster service DNS

## Run locally

Start the worker with the local profile:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Build the jar:

```bash
./mvnw clean package
```

Run the test suite:

```bash
./mvnw test
```

## Operational endpoints

The service does not expose application business endpoints. Only actuator endpoints are available:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/info`

## Docker

```bash
./mvnw clean package -DskipTests
docker build -f Dockerfile -t scaffoldops/generator-worker:latest .
```

## Kubernetes

Deployment manifests live under [`k8s/deployment`](/home/victor/workspace/ScaffoldOps/generator-worker/k8s/deployment):

- [`generator-worker-deployment.yaml`](/home/victor/workspace/ScaffoldOps/generator-worker/k8s/deployment/generator-worker-deployment.yaml)
- [`generator-worker-service.yaml`](/home/victor/workspace/ScaffoldOps/generator-worker/k8s/deployment/generator-worker-service.yaml)

The included deployment currently:

- Runs one replica
- Uses the `dev` Spring profile
- Exposes container port `8080`
- Configures TCP liveness and readiness probes on port `8080`

## Testing

Current automated coverage is focused on:

- Application context startup
- Worker service status transitions for success and failure paths

There are no integration tests for Kafka consumption or downstream lifecycle delivery yet.
