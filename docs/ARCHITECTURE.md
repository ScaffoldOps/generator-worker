# Generator Worker Architecture

## Current scope

This repository provides the first Kafka-consuming worker iteration for ScaffoldOps generation requests.

Included now:

- Spring Boot application bootstrap
- Hexagonal package layout
- Explicit Kafka consumer configuration for `generation-requested`
- Inbound Kafka listener with required-field validation
- Application service that owns placeholder lifecycle transitions
- Placeholder lifecycle and project-generation outbound adapters
- Docker, CI/CD, and Kubernetes deployment baseline

## Package boundaries

- `domain`
  - `GenerationRequestedEvent`: inbound Kafka payload contract
  - `GenerationRequest`: internal application model
  - `GenerationLifecycleUpdate`: outbound lifecycle update model
- `application`
  - `ProcessGenerationRequestUseCase`: inbound use case
  - `GenerationLifecyclePort`: outbound status-update port
  - `ProjectGenerationPort`: outbound generation port
  - `ProcessGenerationRequestService`: lifecycle orchestration service
- `infrastructure`
  - `GenerationRequestedKafkaListener`: inbound Kafka adapter
  - `KafkaConfiguration`: explicit consumer and deserializer wiring
  - `LoggingGenerationLifecycleAdapter`: placeholder lifecycle adapter
  - `NoOpProjectGenerationAdapter`: placeholder generation adapter

Dependency direction remains one-way:

`infrastructure -> application -> domain`

## Runtime flow

1. `generator-api` publishes a JSON event to Kafka topic `generation-requested`.
2. `GenerationRequestedKafkaListener` consumes the event using consumer group `generator-worker`.
3. The listener validates required fields before delegating to the application layer.
4. `ProcessGenerationRequestService` logs processing and emits `RECEIVED`.
5. The same service emits `GENERATING` and calls `ProjectGenerationPort`.
6. On success, the service emits `GENERATED`.
7. On runtime failure, the service emits `FAILED` and rethrows.

## Kafka configuration

Base configuration is defined in `src/main/resources/application.yml`.

Explicit settings:

- `spring.kafka.bootstrap-servers`
- `spring.kafka.consumer.group-id`
- `spring.kafka.consumer.auto-offset-reset=earliest`
- `spring.kafka.consumer.enable-auto-commit=true`
- `spring.kafka.listener.ack-mode=record`
- `spring.kafka.listener.concurrency=1`
- `JsonDeserializer` trusted package `com.scaffoldops.generatorworker.domain.event`
- `JsonDeserializer` default type `GenerationRequestedEvent`
- type headers disabled

`application-dev.yml` points Kafka to `kafka.scaffoldops-dev.svc.cluster.local:9092`.

## Intentional placeholders

- No real scaffold generation engine yet
- No real generator-api request-state update integration yet
- No persistence layer or recovery workflow yet
- No dead-letter, retry, or idempotency design yet
- No public HTTP controller surface for generation requests
