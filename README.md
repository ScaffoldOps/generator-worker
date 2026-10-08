# Generator Worker

`generator-worker` is the ScaffoldOps background worker responsible for consuming generation requests from Kafka, generating projects, publishing artifacts, and reporting generation results to generator-api. Deployment orchestration belongs to generator-api.

## Consumed Kafka topic

- Topic: `generation-requested`
- Topic: `artifact-cleanup-requested`
- Consumer group: `generator-worker`

In `dev`, Kafka defaults to `kafka.scaffoldops-dev.svc.cluster.local:9092`.

## High-level flow

1. `generator-api` publishes a JSON `generation-requested` event to Kafka.
2. `generator-worker` consumes the event through `GenerationRequestedKafkaListener`.
3. The listener validates required fields and logs receipt with `requestId` and requested service name.
4. The service reports `GENERATING` and generates a Spring Boot project in its filesystem workspace.
5. `ArtifactPublisher` finalizes `generation-manifest.json` with the intended artifact and image references. In MinIO mode, it creates a ZIP containing the project files at the archive root and uploads `<requestId>/project.zip`.
6. `ImageBuilderPort` builds the image from the local workspace when enabled, then pushes the same image reference when push is enabled.
7. The worker reports `GENERATED` with `artifactRef` and `imageRef` after every configured step succeeds. With Docker build disabled, `imageRef` is null.
8. Generation, packaging/upload, build, or push failures report `FAILED` and propagate for Kafka retry. The worker no longer publishes deployment requests.

## Artifact cleanup flow

Generated project artifacts live under:

```text
/var/lib/generator-worker/manifests/<serviceName>-<requestId>/
```

In filesystem mode, the `artifactRef` reported back to `generator-api` is:

```text
file:///var/lib/generator-worker/manifests/<serviceName>-<requestId>/
```

Generated artifacts include `pom.xml`, `Dockerfile`, Kubernetes manifests,
`HelloApplication.java`, `HelloController.java`, and
`generation-manifest.json`.

When `generator-api` deletes a generation request, it publishes an
`artifact-cleanup-requested` event. `generator-worker` consumes that event and
deletes the matching generated artifact directory from its configured
`GENERATION_MANIFEST_OUTPUT_DIR`.

The cleanup flow is:

```text
DELETE /generation-requests/{id}
  -> generator-api
  -> Kafka topic artifact-cleanup-requested
  -> generator-worker
  -> PVC directory deletion
```

Cleanup is idempotent: if the directory is already absent, the worker logs that
state and treats the cleanup as successful. Cleanup is path-safe: the worker
derives the directory from the stored request id and service name, normalizes
the result, and only deletes below the configured manifest output directory.
Cleanup is eventually consistent, not transactional with the API database
delete; if `generator-worker` is down, cleanup waits until Kafka is consumed.

In MinIO mode, the PVC is workspace storage and the final artifact lives in MinIO.
The existing cleanup consumer only deletes workspace directories; MinIO object
retention must be managed separately (for example through bucket lifecycle rules).

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
- `ARTIFACT_CLEANUP_REQUESTED_TOPIC`
- `GENERATOR_API_BASE_URL`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED`
- `GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH`
- `GENERATOR_API_AUTH_MODE`
- `GENERATOR_API_TOKEN_URL`
- `GENERATOR_API_CLIENT_ID`
- `GENERATOR_API_CLIENT_SECRET`
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
- cleanup Kafka JSON deserialization for `ArtifactCleanupRequestedEvent`

Profile overrides:

- `local`: callback HTTP enabled by default against `http://localhost:8081/api/generator/v1`
- `dev`: Kafka and generator-api cluster DNS values plus worker data directory defaults
- `pre`: pre environment service DNS values plus worker data directory defaults

### Artifact storage and container images

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `GENERATOR_ARTIFACT_STORAGE_TYPE` | `filesystem` | `filesystem` or `minio` |
| `GENERATOR_MINIO_ENDPOINT` | `http://localhost:9000` | Endpoint; use `https://` for TLS |
| `GENERATOR_MINIO_ACCESS_KEY` | `minioadmin` | Local development credential |
| `GENERATOR_MINIO_SECRET_KEY` | `minioadmin` | Local development credential |
| `GENERATOR_MINIO_BUCKET` | `scaffoldops-artifacts` | Pre-existing artifact bucket |
| `GENERATOR_DOCKER_BUILD_ENABLED` | `true` | Build generated images |
| `GENERATOR_DOCKER_PUSH_ENABLED` | `false` | Push after successful build |
| `GENERATOR_IMAGE_REGISTRY` | empty | Registry host, optionally including port; no URL scheme |
| `GENERATOR_IMAGE_REPOSITORY_PREFIX` | `scaffoldops` | Repository path prefix |

MinIO artifacts use `s3://<bucket>/<requestId>/project.zip`. Provision the bucket
and grant the worker permission to upload objects before enabling MinIO mode.
The manifest references are intended destinations until publishing/build/push
succeed; the `GENERATED` callback confirms completion. Retries overwrite the
same object key and reuse the local project workspace.

For registry publishing, enable both Docker flags and configure the registry.
The image reference is `<registry>/<prefix>/<serviceName>:<requestId>`.
Docker must be available to the worker with a reachable daemon. Configure
registry authentication and any insecure registry settings in the Docker
runtime; credentials are not passed on the command line. Images are stored in
the Docker Registry.

The Kubernetes manifest enables MinIO storage at `http://minio:9000` and reads
credentials from Secret `generator-worker-minio`, keys `access-key` and
`secret-key`. Supply that Secret in `scaffoldops-dev` and adjust the endpoint to
your installation. Docker build and push remain disabled in this manifest;
enable both once a Docker runtime and reachable registry are provisioned.
The existing PVC holds working directories; ZIP temporary files are removed
after upload, including on failure.

### Generator API lifecycle callback

The worker patches lifecycle updates at:

`{GENERATOR_API_BASE_URL}{GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH}`

Required local configuration:

- `SPRING_PROFILES_ACTIVE=local`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED=true`
- `GENERATOR_API_BASE_URL=http://localhost:8081/api/generator/v1`
- `GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH=/internal/generation-requests/{requestId}/generation-status`
- `GENERATOR_API_AUTH_MODE=static-token`
- `GENERATOR_API_BEARER_TOKEN=<JWT accepted by generator-api>`

The `local` profile defaults `GENERATOR_API_LIFECYCLE_HTTP_ENABLED` to `true`. Set it to `false` explicitly to run without `generator-api`.
It also defaults `GENERATOR_API_AUTH_MODE` to `static-token`, so local
development can still use an already-issued JWT.

Example:

```bash
SPRING_PROFILES_ACTIVE=local \
GENERATOR_API_LIFECYCLE_HTTP_ENABLED=true \
GENERATOR_API_BASE_URL=http://localhost:8081/api/generator/v1 \
GENERATOR_API_AUTH_MODE=static-token \
GENERATOR_API_BEARER_TOKEN="$TOKEN" \
./mvnw spring-boot:run
```

The callback uses HTTP `PATCH` and sends `generationStatus`, `message`, `artifactRef`,
and `imageRef`. `artifactRef` and `imageRef` are populated for `GENERATED`,
after the Docker image build succeeds. Only `GENERATING`, `GENERATED`, and
`FAILED` are sent over HTTP; worker-internal transitions such as `RECEIVED`
are not sent.

For Kubernetes, worker-to-api communication uses Keycloak client credentials.
Configure a confidential Keycloak client for the worker and provide:

```bash
GENERATOR_API_AUTH_MODE=client-credentials
GENERATOR_API_TOKEN_URL=http://keycloak-dev.security.svc.cluster.local:8080/realms/scaffoldops-dev/protocol/openid-connect/token
GENERATOR_API_CLIENT_ID=scaffoldops-generator-worker
GENERATOR_API_CLIENT_SECRET=<client secret>
```

The Keycloak token endpoint is environment-specific. DEV uses
`keycloak-dev.security.svc.cluster.local`, PRE uses
`keycloak-pre.security.svc.cluster.local`, and the full token endpoint can be
overridden through `GENERATOR_API_TOKEN_URL`.

The worker obtains access tokens with `grant_type=client_credentials`, caches
the token in memory until shortly before expiration, and retries a lifecycle
callback once with a refreshed token after a `401 Unauthorized`. Tokens and
client secrets are not written to files and must not be logged.

`GENERATOR_API_BEARER_TOKEN` remains only as an optional fallback for local
development when `GENERATOR_API_AUTH_MODE=static-token`, or when
`client-credentials` is selected but the client credentials are not configured.
The token is sent as `Authorization: Bearer <token>`. Leaving all auth variables
empty is supported only when the target endpoint is configured without
authentication.

## Architecture

Hexagonal boundaries are kept explicit:

- `domain`: inbound/outbound event contracts and request/artifact/lifecycle models
- `application`: `ProcessGenerationRequestUseCase` plus outbound ports
- `infrastructure`: Kafka listener, Kafka config, generator-api lifecycle adapter, project generator, filesystem/MinIO artifact publishers, Docker image builder

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

Trigger cleanup through the API by deleting the source request:

```bash
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  ls -la /var/lib/generator-worker/manifests
curl -fsS -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/generator/v1/generation-requests/<requestId>"
kubectl -n scaffoldops-dev logs deploy/generator-worker --tail=150 | grep -i cleanup
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  test ! -d /var/lib/generator-worker/manifests/<serviceName>-<requestId>
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
- `ARTIFACT_CLEANUP_REQUESTED_TOPIC`
- `GENERATOR_API_BASE_URL`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED`
- `GENERATOR_API_BEARER_TOKEN`
- `GENERATION_MANIFEST_OUTPUT_DIR`
- `GENERATION_HANDOFF_STATE_DIR`
- `GENERATOR_DOCKER_BUILD_ENABLED`

## Notes

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current worker boundaries.
