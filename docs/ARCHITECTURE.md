# Generator Worker Architecture

## Purpose

`generator-worker` is the asynchronous execution side of the ScaffoldOps generation pipeline. It subscribes to accepted generation requests and drives the internal lifecycle for the job.

This repository currently contains the worker shell, not the final generation engine.

## Package boundaries

- `com.scaffoldops.generatorworker.domain`
  - `GenerationRequestedEvent`: inbound Kafka payload model
  - `GenerationJob`: internal job model used by the application layer
  - `GenerationLifecycleUpdate`: outbound lifecycle update model
- `com.scaffoldops.generatorworker.application`
  - `ProcessGenerationJobUseCase`: worker use case contract
  - `GenerationLifecyclePort`: outbound port for reporting status changes
  - `ProjectGenerationPort`: outbound port for running generation work
  - `GenerationWorkerService`: orchestration service
- `com.scaffoldops.generatorworker.infrastructure`
  - `KafkaGenerationJobConsumer`: Kafka adapter for inbound events
  - `KafkaConfiguration`: typed Kafka consumer configuration
  - `LoggingGenerationLifecycleAdapter`: placeholder lifecycle adapter
  - `NoOpProjectGenerationAdapter`: placeholder generation adapter

Dependency flow is kept one-way:

`infrastructure -> application -> domain`

## Runtime flow

1. `generator-api` publishes a `GenerationRequestedEvent` to the configured Kafka topic.
2. `KafkaGenerationJobConsumer` receives the event and maps it into a `ProcessGenerationJobUseCase.Command`.
3. `GenerationWorkerService` constructs a `GenerationJob`.
4. The worker emits a `GENERATING` lifecycle update through `GenerationLifecyclePort`.
5. The worker invokes `ProjectGenerationPort.generate(job)`.
6. If generation completes normally, the worker emits a `GENERATED` update.
7. If generation throws a runtime exception, the worker emits a `FAILED` update and rethrows the exception.

## Current adapter implementations

### Inbound

`KafkaGenerationJobConsumer` is the only inbound adapter. It listens to:

- Topic property: `app.kafka.topics.generation-requested`
- Consumer group property: `spring.kafka.consumer.group-id`

Deserialization details:

- Key deserializer: `StringDeserializer`
- Value deserializer: Spring Kafka `JsonDeserializer`
- Trusted package: `com.scaffoldops.generatorworker.domain.event`
- Default value type: `GenerationRequestedEvent`
- Type headers are disabled

### Outbound

Two outbound adapters are intentionally temporary:

- `LoggingGenerationLifecycleAdapter`
  - Accepts lifecycle updates and logs them with the configured lifecycle base URL
  - Does not perform HTTP calls or persistence
- `NoOpProjectGenerationAdapter`
  - Logs that generation was invoked
  - Does not create files, repositories, or deployment assets

## Operational surface

This service is a background worker. It does not expose REST endpoints for generation requests.

The HTTP server exists for operational concerns only:

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/info`

## Configuration model

Base configuration is defined in [`application.yml`](/home/victor/workspace/ScaffoldOps/generator-worker/src/main/resources/application.yml), with profile-specific overrides in:

- [`application-local.yml`](/home/victor/workspace/ScaffoldOps/generator-worker/src/main/resources/application-local.yml)
- [`application-dev.yml`](/home/victor/workspace/ScaffoldOps/generator-worker/src/main/resources/application-dev.yml)
- [`application-pre.yml`](/home/victor/workspace/ScaffoldOps/generator-worker/src/main/resources/application-pre.yml)

Important settings:

- `spring.kafka.bootstrap-servers`
- `spring.kafka.consumer.group-id`
- `app.kafka.topics.generation-requested`
- `app.lifecycle.base-url`

## Testing status

Current tests verify:

- Spring application context startup
- Success path: `GENERATING -> GENERATED`
- Failure path: `GENERATING -> FAILED`

Not covered yet:

- Kafka listener integration
- Serialization compatibility with external publishers
- Retry and error-handler behavior
- Real lifecycle delivery
- Real generation execution

## Intentional gaps

The following capabilities are still pending by design:

- Real scaffold generation implementation
- Real downstream lifecycle update integration
- Persistence or job recovery workflow
- Explicit dead-letter or retry strategy
- Idempotency protections for duplicate Kafka events
- Artifact storage and delivery
