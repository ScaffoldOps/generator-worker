# Generator Worker

`generator-worker` is the ScaffoldOps background worker responsible for consuming generation requests from Kafka and advancing generation lifecycle stages asynchronously.

## Consumed Kafka topic

- Topic: `generation-requested`
- Consumer group: `generator-worker`

In `dev`, Kafka defaults to `kafka.scaffoldops-dev.svc.cluster.local:9092`.

## High-level flow

1. `generator-api` publishes a JSON `generation-requested` event to Kafka.
2. `generator-worker` consumes the event through `GenerationRequestedKafkaListener`.
3. The listener validates required fields and logs receipt with `requestId` and requested service name.
4. `ProcessGenerationRequestService` owns the placeholder lifecycle flow:
   `RECEIVED -> GENERATING -> GENERATED`
5. If placeholder processing throws, the service emits `FAILED`.

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
- `GENERATOR_API_BASE_URL`
- `SERVER_PORT`

Kafka settings are configured explicitly in `src/main/resources/application.yml`:

- JSON deserialization via Spring Kafka `JsonDeserializer`
- default payload type `GenerationRequestedEvent`
- consumer group `generator-worker`
- listener `ack-mode=record`
- listener concurrency `1`

Profile overrides:

- `local`: local lifecycle base URL
- `dev`: Kafka `kafka.scaffoldops-dev.svc.cluster.local:9092`
- `pre`: pre environment service DNS values

## Architecture

Hexagonal boundaries are kept explicit:

- `domain`: message contract and lifecycle/request models
- `application`: `ProcessGenerationRequestUseCase` plus outbound ports
- `infrastructure`: Kafka listener, Kafka config, placeholder lifecycle adapter, placeholder generation adapter

Dependency direction:
`infrastructure -> application -> domain`

There is intentionally no public API layer in this service because it is a worker, not the synchronous entrypoint.

## Current limitations

- No real generation engine yet
- No persistence layer in this service yet
- No real generator-api request-state update integration yet
- No retry, dead-letter, or idempotency workflow yet

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

## Notes

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current worker boundaries.
