# Minikube Generator MVP Demo

This demo validates the current MVP path:

1. `generator-api` accepts a generation request.
2. `generator-api` publishes `generation-requested` to Kafka.
3. `generator-worker` consumes the event.
4. `generator-worker` writes a Spring Boot Hello World project.
5. `generator-worker` runs `docker build`.
6. `generator-worker` patches `generator-api` to `GENERATED`.

`deployment-worker` and a real artifact store are intentionally out of scope.

## Current Minikube State

The expected shared services are in `scaffoldops-dev`:

- `generator-api-deployment`
- `postgres`
- `kafka`
- `kafka-ui`
- topic `generation-requested`

Check the live cluster:

```bash
kubectl get pods,svc,deploy,job -A
kubectl -n scaffoldops-dev get pods,svc,deploy,job
```

As of 2026-06-29, `generator-worker` was not deployed in Minikube. The worker
repo contains Kubernetes manifests under `k8s/deployment`, but `platform-infra`
does not include application workloads for `generator-worker`.

## Docker Build Limitation In A Pod

The current `generator-worker` image is based on `eclipse-temurin:17-jdk` and
does not include the Docker CLI. The deployment also does not mount
`/var/run/docker.sock` and does not run a Docker daemon sidecar. Therefore the
worker cannot complete the current `docker build` step inside Kubernetes as-is.

For the MVP, use the local worker path below. It lets the worker connect to
Kafka and `generator-api` in Minikube while using the host Docker daemon for
image builds.

Future options:

- mount `/var/run/docker.sock` into the worker pod for local-only Minikube demos;
- replace the direct Docker CLI call with Kaniko or BuildKit;
- publish generated artifacts to a real artifact store before adding
  `deployment-worker`.

## 1. Start Platform Infra

From `platform-infra`:

```bash
kubectl apply -k k8s/overlays/local-dev
```

Verify the pods:

```bash
kubectl -n scaffoldops-dev get pods
kubectl -n scaffoldops get pods
kubectl -n security get pods
```

Kafka and Kafka UI should be running in `scaffoldops-dev`. If Keycloak is not
healthy, obtain or reuse a JWT that the already deployed `generator-api`
accepts before running the callback steps.

## 2. Verify Generator API

```bash
kubectl -n scaffoldops-dev get deploy,svc generator-api-deployment generator-api-service
kubectl -n scaffoldops-dev port-forward svc/generator-api-service 8081:80
```

In another terminal:

```bash
curl -fsS http://localhost:8081/api/generator/v1/actuator/health
```

## 3. Verify Kafka

```bash
kubectl -n scaffoldops-dev exec deploy/kafka -- kafka-topics \
  --bootstrap-server kafka:9092 \
  --list
```

The output must include:

```text
generation-requested
```

Optional Kafka UI:

```bash
kubectl -n scaffoldops-dev port-forward svc/kafka-ui 8080:8080
```

Open `http://localhost:8080` and inspect the `generation-requested` topic.

## 4. Run Generator Worker Locally

Forward Kafka to the workstation:

```bash
kubectl -n scaffoldops-dev port-forward svc/kafka 9092:9092
```

In another terminal, from `generator-worker`:

```bash
rm -rf /tmp/scaffoldops-minikube-worker

SPRING_PROFILES_ACTIVE=local \
SERVER_PORT=8082 \
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
GENERATION_REQUESTED_TOPIC=generation-requested \
GENERATOR_API_LIFECYCLE_HTTP_ENABLED=true \
GENERATOR_API_BASE_URL=http://localhost:8081/api/generator/v1 \
GENERATOR_API_LIFECYCLE_STATUS_UPDATE_PATH=/internal/generation-requests/{requestId}/status \
GENERATOR_API_AUTH_MODE=static-token \
GENERATOR_API_BEARER_TOKEN="$TOKEN" \
GENERATION_MANIFEST_OUTPUT_DIR=/tmp/scaffoldops-minikube-worker/manifests \
GENERATION_HANDOFF_STATE_DIR=/tmp/scaffoldops-minikube-worker/handoff \
DOCKER_COMMAND=docker \
./mvnw spring-boot:run
```

`GENERATOR_API_AUTH_MODE=static-token` keeps the local demo on an already-issued
JWT. Cluster deployments should use Keycloak client credentials instead.

## 5. Create A Generation Request

In a terminal with `TOKEN` set to a JWT accepted by `generator-api`:

```bash
export SERVICE_NAME="hello-demo-$(date +%s)"

curl -fsS -X POST \
  http://localhost:8081/api/generator/v1/generation-requests \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{
    \"name\": \"$SERVICE_NAME\",
    \"template\": \"spring-boot-hexagonal\",
    \"database\": false,
    \"restApi\": true,
    \"security\": false,
    \"messaging\": false,
    \"deploymentTarget\": \"KUBERNETES\"
  }" | tee /tmp/scaffoldops-generation-request.json

export REQUEST_ID="$(jq -r '.id' /tmp/scaffoldops-generation-request.json)"
```

## 6. Check Kafka Consumption

Check the worker logs for:

```text
Received generation-requested event
Processing generation request
```

Check the consumer group:

```bash
kubectl -n scaffoldops-dev exec deploy/kafka -- kafka-consumer-groups \
  --bootstrap-server kafka:9092 \
  --describe \
  --group generator-worker
```

For a completed demo, lag should return to `0`.

## 7. Check Generated Project

```bash
export PROJECT_DIR="/tmp/scaffoldops-minikube-worker/manifests/$SERVICE_NAME-$REQUEST_ID"

test -f "$PROJECT_DIR/pom.xml"
test -f "$PROJECT_DIR/Dockerfile"
find "$PROJECT_DIR/src/main/java" -name 'HelloApplication.java' -print -quit
find "$PROJECT_DIR/src/main/java" -name 'HelloController.java' -print -quit
```

## 8. Check Docker Build

```bash
export IMAGE_REF="scaffoldops/$SERVICE_NAME:$REQUEST_ID"

docker image inspect "$IMAGE_REF" >/dev/null
docker images --format '{{.Repository}}:{{.Tag}}' | grep -Fx "$IMAGE_REF"
```

The worker log should include:

```text
Built generated Docker image
```

## 9. Check Final API State

```bash
for attempt in $(seq 1 60); do
  curl -fsS \
    -H "Authorization: Bearer $TOKEN" \
    "http://localhost:8081/api/generator/v1/generation-requests/$REQUEST_ID" \
    > /tmp/scaffoldops-generation-result.json

  STATUS="$(jq -r '.status' /tmp/scaffoldops-generation-result.json)"
  case "$STATUS" in
    GENERATED|FAILED) break ;;
  esac
  sleep 2
done

jq . /tmp/scaffoldops-generation-result.json
```

Validate success:

```bash
jq -e \
  --arg imageRef "$IMAGE_REF" \
  '.status == "GENERATED"
   and (.artifactRef | startswith("file:"))
   and .imageRef == $imageRef' \
  /tmp/scaffoldops-generation-result.json
```

## Optional Kubernetes Worker Deployment

The Kubernetes manifests are present for smoke testing the pod wiring:

```bash
kubectl apply -k k8s/deployment
kubectl -n scaffoldops-dev get deploy,pod,svc -l app=generator-worker
kubectl rollout status deployment/generator-worker -n scaffoldops-dev --timeout=300s
kubectl -n scaffoldops-dev logs deploy/generator-worker -f
```

They configure:

- `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`
- `GENERATION_REQUESTED_TOPIC=generation-requested`
- `GENERATOR_API_BASE_URL=http://generator-api:8081/api/generator/v1`
- `GENERATOR_API_LIFECYCLE_HTTP_ENABLED=true`
- `GENERATOR_API_AUTH_MODE=client-credentials`
- `GENERATOR_API_TOKEN_URL=http://keycloak-dev.security.svc.cluster.local:8080/realms/scaffoldops-dev/protocol/openid-connect/token`
- `GENERATOR_API_CLIENT_ID=scaffoldops-generator-worker`
- `GENERATOR_API_CLIENT_SECRET` from secret `generator-api-worker-client`,
  key `client-secret`
- `GENERATOR_DOCKER_BUILD_ENABLED=false`
- generated output under `/var/lib/generator-worker`

For deployed environments, Keycloak service names are environment-specific:
DEV uses `keycloak-dev.security.svc.cluster.local`, PRE uses
`keycloak-pre.security.svc.cluster.local`, and the full endpoint remains
configurable through `GENERATOR_API_TOKEN_URL`.

Create or update the client secret before applying the worker manifests:

```bash
kubectl -n scaffoldops-dev create secret generic generator-api-worker-client \
  --from-literal=client-secret="$GENERATOR_API_CLIENT_SECRET" \
  --dry-run=client -o yaml | kubectl apply -f -
```

The worker exchanges that client secret for access tokens using Keycloak's
`client_credentials` flow. Tokens are cached in memory and refreshed shortly
before expiration; a lifecycle callback that receives `401 Unauthorized` is
retried once after forcing a refresh.

The manifests also create a `generator-api` Service alias on port `8081`
because the existing API service is named `generator-api-service` and exposes
port `80`.

In this Kubernetes mode the worker skips the direct `docker build` step because
the pod does not have Docker access. Use the local-worker path above when the
demo must prove that the generated Docker image is built.

## Kubernetes Artifact Persistence

The Kubernetes worker deployment mounts
`generator-worker-artifacts-pvc` at `/var/lib/generator-worker`. Generated
projects stay under the current output directory:

```text
/var/lib/generator-worker/manifests/<serviceName>-<requestId>/
```

The callback `artifactRef` continues to use the pod-local file URI:

```text
file:///var/lib/generator-worker/manifests/<serviceName>-<requestId>/
```

This PVC is only MVP persistence so artifacts survive a worker pod restart. It
is not a real Artifact Store: there is still no MinIO/S3-backed store, no
download API, and `deployment-worker` remains outside this MVP.

When a generation request is deleted through `generator-api`, the API publishes
an `artifact-cleanup-requested` Kafka event. `generator-worker` consumes it and
deletes the matching directory below `/var/lib/generator-worker/manifests`.
Cleanup is idempotent, so a missing directory is logged and treated as success.
Cleanup is eventually consistent, not transactional with the API database
delete; if `generator-worker` is down, cleanup waits until Kafka is consumed.

Verify the PVC and inspect generated files:

```bash
kubectl -n scaffoldops-dev get pvc
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  find /var/lib/generator-worker/manifests -maxdepth 4 -type f
```

Copy a generated project from the pod to the host:

```bash
kubectl -n scaffoldops-dev cp \
  deploy/generator-worker:/var/lib/generator-worker/manifests/<serviceName>-<requestId> \
  ./<serviceName>-<requestId>
```

Check that artifacts survive a rollout restart:

```bash
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  find /var/lib/generator-worker/manifests -maxdepth 4 -type f
kubectl -n scaffoldops-dev rollout restart deployment/generator-worker
kubectl -n scaffoldops-dev rollout status deployment/generator-worker --timeout=300s
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  find /var/lib/generator-worker/manifests -maxdepth 4 -type f
```

Check cleanup after deleting the request:

```bash
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  ls -la /var/lib/generator-worker/manifests

curl -fsS -X DELETE \
  -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/generator/v1/generation-requests/$REQUEST_ID"

kubectl -n scaffoldops-dev logs deploy/generator-worker --tail=150 | grep -i cleanup
kubectl -n scaffoldops-dev exec deploy/generator-worker -- \
  test ! -d "/var/lib/generator-worker/manifests/$SERVICE_NAME-$REQUEST_ID"
```
