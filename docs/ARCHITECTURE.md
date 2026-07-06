# Generator Worker Architecture

## Current scope

This repository provides the generator-stage worker for ScaffoldOps generation requests.

Included now:

- Spring Boot application bootstrap
- Hexagonal package layout
- Explicit Kafka consumer configuration for `generation-requested`
- Inbound Kafka listener with required-field validation
- Application service that orchestrates generation-stage lifecycle transitions
- Project generation adapter that produces a Spring Boot Hello World service and durable handoff reference
- Docker image builder that tags the generated service locally without publishing it
- Outbound Kafka publication for `deployment-requested`
- Optional HTTP-backed lifecycle adapter for `generator-api`
- Local requestId-based idempotency for manifest reuse and deployment-request publication
- Docker, CI/CD, and Kubernetes deployment baseline

## Package boundaries

- `domain`
  - `GenerationRequestedEvent`: inbound Kafka payload contract
- `DeploymentRequestedEvent`: outbound Kafka payload contract
- `ArtifactCleanupRequestedEvent`: inbound Kafka payload contract for artifact cleanup after request deletion
  - `GenerationRequest`: internal application model
  - `GenerationArtifact`: durable generation result model
  - `GenerationLifecycleUpdate`: outbound lifecycle update model
- `application`
  - `ProcessGenerationRequestUseCase`: inbound use case
  - `GenerationLifecyclePort`: outbound status-update port
  - `ProjectGenerationPort`: outbound generation port
  - `ImageBuilderPort`: outbound local image-build port
- `DeploymentRequestedPublisherPort`: outbound deployment-event port
- `CleanupGeneratedArtifactUseCase`: inbound cleanup use case
  - `ProcessGenerationRequestService`: lifecycle orchestration service
- `infrastructure`
- `GenerationRequestedKafkaListener`: inbound Kafka adapter
- `ArtifactCleanupRequestedKafkaListener`: inbound cleanup Kafka adapter
  - `KafkaConfiguration`: explicit consumer and deserializer wiring
  - `GeneratorApiGenerationLifecycleAdapter`: optional generator-api lifecycle adapter
  - `ManifestWritingProjectGenerationAdapter`: deterministic manifest writer
  - `DockerImageBuilderAdapter`: local `docker build` adapter
- `DeploymentRequestedKafkaPublisher`: outbound Kafka deployment-event adapter
- `CleanupGeneratedArtifactService`: deletes generated artifact directories under the configured manifest output directory

Dependency direction remains one-way:

`infrastructure -> application -> domain`

## Runtime flow

1. `generator-api` publishes a JSON event to Kafka topic `generation-requested`.
2. `GenerationRequestedKafkaListener` consumes the event using consumer group `generator-worker`.
3. The listener validates required fields before delegating to the application layer.
4. `ProcessGenerationRequestService` emits `RECEIVED` and `GENERATING` through `GenerationLifecyclePort`.
5. The same service calls `ProjectGenerationPort`, which writes a deterministic Spring Boot project keyed by `requestId`.
6. On success, the service emits `GENERATED` with the durable artifact reference.
7. The service runs `docker build --tag scaffoldops/<serviceName>:<requestId> <projectDirectory>`.
8. The service publishes `DeploymentRequestedEvent` to Kafka topic `deployment-requested`.
9. After successful publication, the service emits `DEPLOYMENT_REQUESTED`.
10. On generation, image-build, or publication failure, the service emits `FAILED` and rethrows.

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

Cleanup uses topic `artifact-cleanup-requested`. `generator-api` owns request
lifecycle state and publishes this event after a generation request is deleted;
`generator-worker` owns generated artifacts and consumes the event to remove
`/var/lib/generator-worker/manifests/<serviceName>-<requestId>/` from the
PVC-backed manifest directory. `generator-api` must not access the worker PVC
directly. Missing directories are treated as success, and cleanup rejects paths
that would escape the configured manifest output directory. Cleanup is
eventually consistent, not transactional with the API database delete.

## Producer-side durability and idempotency

- Projects are written to `app.generation.manifest-output-dir`
- Project directories are deterministic: `<serviceName>-<requestId>`
- A `generation-manifest.json` file records the resolved service, package, image, and request variables
- Generated output includes `pom.xml`, `Dockerfile`, Kubernetes manifests, `HelloApplication.java`, `HelloController.java`, and `generation-manifest.json`
- Existing completed projects are reused for repeat processing of the same `requestId`
- Successful `deployment-requested` publications create a local marker file under `app.generation.handoff-state-dir`
- Existing marker files suppress duplicate `deployment-requested` publication on the same worker filesystem

These safeguards are local to the worker filesystem. They improve single-node or sticky-volume behavior but are not a substitute for cross-instance distributed idempotency.

## Lifecycle integration

- `GenerationLifecyclePort` remains the only lifecycle update path from the application layer
- `GeneratorApiGenerationLifecycleAdapter` can PATCH status updates to `generator-api` when `app.lifecycle.http-enabled=true`
- The `local` profile enables HTTP callbacks by default; `dev` and `pre` require explicit enablement
- Callback configuration is supplied through `GENERATOR_API_BASE_URL`, `GENERATOR_API_LIFECYCLE_HTTP_ENABLED`, `GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH`, and `GENERATOR_API_BEARER_TOKEN`
- HTTP contract tests validate `GENERATING`, `GENERATED`, and `FAILED` payloads
- `RECEIVED` and `DEPLOYMENT_REQUESTED` remain worker-local transitions and are not sent to `generator-api`
- When HTTP lifecycle updates are disabled, the adapter logs the transition instead

The callback uses `PATCH {baseUrl}{statusUpdatePath}` with `status`, `message`,
`artifactRef`, and `imageRef` in the JSON body. The request id is supplied only
as a path variable. A configured bearer token is added to the Authorization
header.

`GENERATED` is emitted only after project generation and Docker image build
both succeed. Generation or image build failures emit `FAILED`.

## Remaining gaps

- Generated output is intentionally limited to a minimal Hello World Spring Boot service
- Cross-instance idempotency is not solved yet
- No dead-letter topic or outbox/reconciliation workflow yet
- Service-account token acquisition is still external configuration
- No public HTTP controller surface for generation requests
