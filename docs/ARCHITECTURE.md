# Generator Worker Architecture

## Current scope

This repository provides the generator-stage worker for ScaffoldOps generation requests.

Included now:

- Spring Boot application bootstrap
- Hexagonal package layout
- Explicit Kafka consumer configuration for `generation-requested`
- Inbound Kafka listener with required-field validation
- Application service that orchestrates generation-stage lifecycle transitions
- Manifest-writing generation adapter that produces a durable handoff reference
- Outbound Kafka publication for `deployment-requested`
- Optional HTTP-backed lifecycle adapter for `generator-api`
- Local requestId-based idempotency for manifest reuse and deployment-request publication
- Docker, CI/CD, and Kubernetes deployment baseline

## Package boundaries

- `domain`
  - `GenerationRequestedEvent`: inbound Kafka payload contract
  - `DeploymentRequestedEvent`: outbound Kafka payload contract
  - `GenerationRequest`: internal application model
  - `GenerationArtifact`: durable generation result model
  - `GenerationLifecycleUpdate`: outbound lifecycle update model
- `application`
  - `ProcessGenerationRequestUseCase`: inbound use case
  - `GenerationLifecyclePort`: outbound status-update port
  - `ProjectGenerationPort`: outbound generation port
  - `DeploymentRequestedPublisherPort`: outbound deployment-event port
  - `ProcessGenerationRequestService`: lifecycle orchestration service
- `infrastructure`
  - `GenerationRequestedKafkaListener`: inbound Kafka adapter
  - `KafkaConfiguration`: explicit consumer and deserializer wiring
  - `GeneratorApiGenerationLifecycleAdapter`: optional generator-api lifecycle adapter
  - `ManifestWritingProjectGenerationAdapter`: deterministic manifest writer
  - `DeploymentRequestedKafkaPublisher`: outbound Kafka deployment-event adapter

Dependency direction remains one-way:

`infrastructure -> application -> domain`

## Runtime flow

1. `generator-api` publishes a JSON event to Kafka topic `generation-requested`.
2. `GenerationRequestedKafkaListener` consumes the event using consumer group `generator-worker`.
3. The listener validates required fields before delegating to the application layer.
4. `ProcessGenerationRequestService` emits `RECEIVED` and `GENERATING` through `GenerationLifecyclePort`.
5. The same service calls `ProjectGenerationPort`, which writes a deterministic manifest file keyed by `requestId`.
6. On success, the service emits `GENERATED` with the durable artifact reference.
7. The service publishes `DeploymentRequestedEvent` to Kafka topic `deployment-requested`.
8. After successful publication, the service emits `DEPLOYMENT_REQUESTED`.
9. On runtime failure, the service emits `FAILED` and rethrows.

## Kafka configuration

Base configuration is defined in `src/main/resources/application.yml`.

Explicit settings:

- `spring.kafka.bootstrap-servers`
- `spring.kafka.consumer.group-id`
- `spring.kafka.consumer.auto-offset-reset=earliest`
- `spring.kafka.consumer.enable-auto-commit=false`
- `spring.kafka.listener.ack-mode=record`
- `spring.kafka.listener.concurrency=1`
- `DefaultErrorHandler` with fixed backoff retry for listener failures
- `JsonDeserializer` trusted package `com.scaffoldops.generatorworker.domain.event`
- `JsonDeserializer` default type `GenerationRequestedEvent`
- type headers disabled
- JSON serialization for outbound `DeploymentRequestedEvent`

`application-dev.yml` points Kafka to `kafka.scaffoldops-dev.svc.cluster.local:9092`.

## Producer-side durability and idempotency

- Manifests are written to `app.generation.manifest-output-dir`
- Manifest filenames are deterministic: `manifest-<requestId>.json`
- Existing manifests are reused for repeat processing of the same `requestId`
- Successful `deployment-requested` publications create a local marker file under `app.generation.handoff-state-dir`
- Existing marker files suppress duplicate `deployment-requested` publication on the same worker filesystem

These safeguards are local to the worker filesystem. They improve single-node or sticky-volume behavior but are not a substitute for cross-instance distributed idempotency.

## Lifecycle integration

- `GenerationLifecyclePort` remains the only lifecycle update path from the application layer
- `GeneratorApiGenerationLifecycleAdapter` can POST status updates to `generator-api` when `app.lifecycle.http-enabled=true`
- When HTTP lifecycle updates are disabled, the adapter logs the transition instead

This preserves `generator-api` as the intended lifecycle system of record without blocking worker progress on a missing callback rollout.

## Remaining gaps

- Manifest output is a minimal durable handoff artifact, not a full scaffold package
- Cross-instance idempotency is not solved yet
- No dead-letter topic or outbox/reconciliation workflow yet
- `generator-api` lifecycle callback contract is not finalized in this repository
- No public HTTP controller surface for generation requests
