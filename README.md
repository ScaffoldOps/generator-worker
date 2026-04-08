# Generator Worker

`generator-worker` is the ScaffoldOps background worker responsible for consuming generation requests from Kafka, performing generation-stage execution, and handing successful generation results off to deployment via Kafka.

## Consumed Kafka topic

- Topic: `generation-requested`
- Consumer group: `generator-worker`

## Produced Kafka topic

- Topic: `deployment-requested`

In `dev`, Kafka defaults to `kafka.scaffoldops-dev.svc.cluster.local:9092`.

## High-level flow

1. `generator-api` publishes a JSON `generation-requested` event to Kafka.
2. `generator-worker` consumes the event through `GenerationRequestedKafkaListener`.
3. The listener validates required fields and logs receipt with `requestId` and requested service name.
4. `ProcessGenerationRequestService` owns the generation-stage flow:
   `RECEIVED -> GENERATING -> GENERATED -> DEPLOYMENT_REQUESTED`
5. `ProjectGenerationPort` writes a deterministic manifest artifact keyed by `requestId`.
6. `DeploymentRequestedPublisherPort` publishes `deployment-requested` only after the durable manifest reference exists.
7. If generation or publish processing throws, the service emits `FAILED`.

## Event contract

The worker expects JSON compatible with `GenerationRequestedEvent`:

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
  "createdAt": "2026-03-24T10:15:30Z"
}
```

Required fields currently validated by the Kafka adapter:

- `requestId`
- `name`
- `template`
- `database`
- `restApi`
- `security`
- `messaging`
- `deploymentTarget`
- `status`
- `createdAt`

## Configuration

Main runtime properties:

- `KAFKA_BOOTSTRAP_SERVERS`
- `KAFKA_CONSUMER_GROUP`
- `GENERATION_REQUESTED_TOPIC`
- `DEPLOYMENT_REQUESTED_TOPIC`
- `GENERATOR_API_BASE_URL`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED`
- `GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH`
- `GENERATION_MANIFEST_OUTPUT_DIR`
- `GENERATION_HANDOFF_STATE_DIR`
- `SERVER_PORT`

Kafka settings are configured explicitly in `src/main/resources/application.yml`:

- JSON deserialization via Spring Kafka `JsonDeserializer`
- default payload type `GenerationRequestedEvent`
- consumer group `generator-worker`
- consumer `enable-auto-commit=false`
- listener `ack-mode=record`
- listener concurrency `1`
- listener retry via `DefaultErrorHandler`
- outbound Kafka JSON serialization for `DeploymentRequestedEvent`

Profile overrides:

- `local`: local lifecycle base URL
- `dev`: Kafka and generator-api cluster DNS values plus worker data directory defaults
- `pre`: pre environment service DNS values plus worker data directory defaults

## Architecture

Hexagonal boundaries are kept explicit:

- `domain`: inbound/outbound event contracts and request/artifact/lifecycle models
- `application`: `ProcessGenerationRequestUseCase` plus outbound ports
- `infrastructure`: Kafka listener, Kafka config, generator-api lifecycle adapter, manifest-writing generation adapter, deployment-requested publisher

Dependency direction:
`infrastructure -> application -> domain`

There is intentionally no public API layer in this service because it is a worker, not the synchronous entrypoint.

## Current limitations

- Manifest output is a minimal durable handoff artifact, not a full generated project bundle
- Lifecycle HTTP integration is optional and disabled by default until the `generator-api` contract is finalized
- Idempotency is local to the worker filesystem via deterministic manifests and publication markers
- No dead-letter topic, outbox, or cross-instance reconciliation workflow yet

## Run

Start the worker locally with the `local` profile:

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

## Test

```bash
./mvnw test
```

## Runtime endpoints

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`

## Docker

```bash
./mvnw clean package -DskipTests
docker build -f Dockerfile -t scaffoldops/generator-worker:latest .
```

## Kubernetes

Deployment assets live under `k8s/deployment`.

The deployment mounts `/var/lib/generator-worker` for manifest and handoff marker files and exposes env vars for:

- `GENERATION_MANIFEST_OUTPUT_DIR`
- `GENERATION_HANDOFF_STATE_DIR`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED`

## Notes

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current worker boundaries.
