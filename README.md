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
5. `ProjectGenerationPort` writes a deterministic Spring Boot Hello World project keyed by `requestId`.
6. `ImageBuilderPort` runs `docker build` against the generated project and tags the local image as `scaffoldops/<serviceName>:<requestId>`.
7. `DeploymentRequestedPublisherPort` publishes `deployment-requested` only after the local image build succeeds.
8. If generation, image build, or publish processing throws, the service emits `FAILED`.

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
- `GENERATOR_API_BEARER_TOKEN`
- `GENERATION_MANIFEST_OUTPUT_DIR`
- `GENERATION_HANDOFF_STATE_DIR`
- `DOCKER_COMMAND`
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

- `local`: callback HTTP enabled by default against `http://localhost:8081/api/generator/v1`
- `dev`: Kafka and generator-api cluster DNS values plus worker data directory defaults
- `pre`: pre environment service DNS values plus worker data directory defaults

### Generator API lifecycle callback

The worker patches lifecycle updates at:

`{GENERATOR_API_BASE_URL}{GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH}`

Required local configuration:

- `SPRING_PROFILES_ACTIVE=local`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED=true`
- `GENERATOR_API_BASE_URL=http://localhost:8081/api/generator/v1`
- `GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH=/internal/generation-requests/{requestId}/status`
- `GENERATOR_API_BEARER_TOKEN=<JWT accepted by generator-api>`

The `local` profile defaults `GENERATOR_API_LIFECYCLE_HTTP_ENABLED` to `true`. Set it to `false` explicitly to run without `generator-api`.

Example:

```bash
SPRING_PROFILES_ACTIVE=local \
GENERATOR_API_LIFECYCLE_HTTP_ENABLED=true \
GENERATOR_API_BASE_URL=http://localhost:8081/api/generator/v1 \
GENERATOR_API_BEARER_TOKEN="$TOKEN" \
./mvnw spring-boot:run
```

The callback uses HTTP `PATCH` and sends `status`, `message`, `artifactRef`,
and `imageRef`. `artifactRef` and `imageRef` are populated for `GENERATED`,
after the Docker image build succeeds. Only `GENERATING`, `GENERATED`, and
`FAILED` are sent over HTTP; worker-internal transitions such as `RECEIVED`
and `DEPLOYMENT_REQUESTED` are not sent.

For the local MVP, export a JWT already used to call the protected
`generator-api` endpoints:

```bash
export TOKEN="<generator-api JWT>"
export GENERATOR_API_BEARER_TOKEN="$TOKEN"
```

The token is sent as `Authorization: Bearer <token>`. Leaving the variable
empty is supported only when the target endpoint is configured without
authentication.

## Architecture

Hexagonal boundaries are kept explicit:

- `domain`: inbound/outbound event contracts and request/artifact/lifecycle models
- `application`: `ProcessGenerationRequestUseCase` plus outbound ports
- `infrastructure`: Kafka listener, Kafka config, generator-api lifecycle adapter, project generator, Docker image builder, deployment-requested publisher

Dependency direction:
`infrastructure -> application -> domain`

There is intentionally no public API layer in this service because it is a worker, not the synchronous entrypoint.

## Current limitations

- Generated output is a minimal Spring Boot Hello World project with Docker and Kubernetes assets
- Lifecycle HTTP integration is optional outside the local profile
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
docker build -f Dockerfile -t victodomvar/scaffoldops-generator-worker:latest .
```

## CI/CD

GitHub Actions workflows live under `.github/workflows`:

- `Generator Worker Develop Pipeline` runs on pushes to `develop`.
- `Generator Worker Main Pipeline` runs on pushes to `main`.
- `Generator Worker PR Checks` runs for pull requests and feature branches.
- `Deploy generator-worker to Kubernetes` is the reusable Minikube deployment workflow.

The develop and main pipelines:

1. run `./mvnw --batch-mode --no-transfer-progress clean test`;
2. package the worker jar;
3. build Docker image `victodomvar/scaffoldops-generator-worker`;
4. push both tags to Docker Hub:
   - `victodomvar/scaffoldops-generator-worker:latest`
   - `victodomvar/scaffoldops-generator-worker:<commit-sha>`
5. deploy to Minikube namespace `scaffoldops-dev`.

Required GitHub secrets:

- `DOCKER_USERNAME`
- `DOCKER_PASSWORD`

The self-hosted runner must have Docker available and `kubectl` access to the
local Minikube context named `minikube`.

Manual deployment after an image is available:

```bash
kubectl apply -k k8s/deployment
kubectl rollout restart deployment/generator-worker -n scaffoldops-dev
kubectl rollout status deployment/generator-worker -n scaffoldops-dev --timeout=300s
```

Verify the pod and logs:

```bash
kubectl -n scaffoldops-dev get deploy,pod,svc -l app=generator-worker
kubectl -n scaffoldops-dev logs deploy/generator-worker -f
```

## Kubernetes

Deployment assets live under `k8s/deployment`.

For the current Minikube MVP walkthrough, including the Docker-in-pod
limitation and the recommended local-worker E2E path, see
[docs/minikube-e2e-demo.md](docs/minikube-e2e-demo.md).

The deployment mounts `/var/lib/generator-worker` from the
`generator-worker-artifacts-pvc` PersistentVolumeClaim. Generated projects keep
the existing output path under `/var/lib/generator-worker/manifests`, so
successful callbacks still report artifact references like:

```text
file:///var/lib/generator-worker/manifests/<serviceName>-<requestId>/
```

The PVC is an MVP persistence layer for generated artifacts and local handoff
markers. It survives `generator-worker` pod recreation, but it is not a real
Artifact Store: there is no MinIO/S3 integration, no external artifact API, and
`deployment-worker` remains outside the MVP.

Inspect the generated files in the running pod:

```bash
kubectl -n scaffoldops-dev get pvc
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  find /var/lib/generator-worker/manifests -maxdepth 4 -type f
```

Copy one generated project to the host:

```bash
kubectl -n scaffoldops-dev cp \
  deploy/generator-worker:/var/lib/generator-worker/manifests/<serviceName>-<requestId> \
  ./<serviceName>-<requestId>
```

Check persistence across a pod restart:

```bash
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  find /var/lib/generator-worker/manifests -maxdepth 4 -type f
kubectl -n scaffoldops-dev rollout restart deployment/generator-worker
kubectl -n scaffoldops-dev rollout status deployment/generator-worker --timeout=300s
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  find /var/lib/generator-worker/manifests -maxdepth 4 -type f
```

The deployment exposes env vars for:

- `SPRING_KAFKA_BOOTSTRAP_SERVERS`
- `GENERATION_REQUESTED_TOPIC`
- `GENERATOR_API_BASE_URL`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED`
- `GENERATOR_API_BEARER_TOKEN`
- `GENERATION_MANIFEST_OUTPUT_DIR`
- `GENERATION_HANDOFF_STATE_DIR`
- `GENERATOR_DOCKER_BUILD_ENABLED`

## Notes

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current worker boundaries.
