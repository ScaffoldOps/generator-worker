# generator-worker

Kafka worker that generates a minimal Spring Boot project, uploads a ZIP to MinIO, builds and pushes a Docker image, and reports generation results. Also handles scheduled image recovery and permanent asset cleanup.

Platform guides: [ScaffoldOps documentation](https://github.com/ScaffoldOps/scaffoldops-docs) · [local setup](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/local-development.md) · [configuration](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/configuration.md) · [known gaps](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/findings.md).

## Local requirements

Java 17, Maven (wrapper included), a reachable Kafka broker and authenticated generator-api callbacks (for workers). Install Linux Docker CLI and provide a reachable Docker daemon, MinIO and Docker Hub credentials.

Kafka port-forward alone does not fix broker metadata advertising `kafka:9092`; use a broker with a host-reachable advertised listener for host execution. The cluster path is the documented MVP setup.

## Configuration

| Variable | Use |
| --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Host default `localhost:9092`; DEV broker `kafka:9092` |
| `GENERATOR_API_BASE_URL` | Include `/api/generator/v1`; local profile defaults to `http://localhost:8081/api/generator/v1` |
| `GENERATOR_API_LIFECYCLE_HTTP_ENABLED` | Set `true` for real callbacks |
| `GENERATOR_API_AUTH_MODE` | `client-credentials` or local `static-token` |
| `GENERATOR_API_TOKEN_URL`, `GENERATOR_API_CLIENT_ID`, `GENERATOR_API_CLIENT_SECRET` | OAuth callback credentials |
| `GENERATOR_API_BEARER_TOKEN` | Local static-token alternative |
| `GENERATOR_ARTIFACT_STORAGE_TYPE` | Default `filesystem`; DEV manifest selects `minio` |
| `GENERATOR_MINIO_ENDPOINT`, `GENERATOR_MINIO_BUCKET` | Default `http://localhost:9000`, `scaffoldops-artifacts` |
| `GENERATOR_MINIO_ACCESS_KEY`, `GENERATOR_MINIO_SECRET_KEY` | MinIO credentials |
| `DOCKER_USERNAME`, `DOCKER_PASSWORD` | Docker Hub login credentials for image publishing |
| `GENERATOR_IMAGE_REGISTRY`, `GENERATOR_IMAGE_REPOSITORY_PREFIX` | Default `docker.io`, `victodomvar/scaffoldops-generated` |
| `GENERATION_MANIFEST_OUTPUT_DIR`, `GENERATION_HANDOFF_STATE_DIR` | Workspace and persisted handoff paths |
| `SERVER_PORT` | Default `8080`; use `8082` for host execution |

Namespace-local secrets: `generator-api-worker-client/client-secret`, `generator-worker-minio/access-key` and `secret-key`, and `docker-hub-credentials/DOCKER_USERNAME` and `DOCKER_PASSWORD`. These must exist separately in `scaffoldops-dev` and `scaffoldops-pre`, along with the registry pull secret `docker-hub-pull-secret` (type `kubernetes.io/dockerconfigjson`). Build/push are enabled by default; disabling them cannot produce a successful complete generation. See `src/main/resources/application*.yml` for topics and retry controls.

## Build, test and run

Run from this repository root after provisioning the dependencies and exporting the variables above:

```bash
./mvnw clean test
./mvnw clean package -DskipTests
SPRING_PROFILES_ACTIVE=local SERVER_PORT=8082 GENERATOR_ARTIFACT_STORAGE_TYPE=minio GENERATOR_API_BEARER_TOKEN="$TOKEN" ./mvnw spring-boot:run
```

## Docker and Kubernetes

```bash
docker build -f Dockerfile -t victodomvar/scaffoldops-generator-worker:local .
```

Build the JAR before docker build. CI publishes `victodomvar/scaffoldops-generator-worker` with `latest` and full commit SHA; both branch pipelines deploy the full SHA tag. GitHub Actions requires repository/organization secrets `DOCKER_USERNAME` and `DOCKER_PASSWORD` (a Docker Hub access token).

After applying shared infrastructure and creating component secrets:

```bash
kubectl apply -k k8s/overlays/dev
kubectl -n scaffoldops-dev rollout status deploy/generator-worker
kubectl kustomize k8s/overlays/pre
```

Use `k8s/overlays/dev` or `k8s/overlays/pre`, not the namespace-neutral `k8s/deployment` base. Direct overlay applies use `latest` for local smoke testing; CI renders a temporary copy with the full SHA image tag before a single apply, so no transient DEV profile or mutable image is deployed to PRE.

Both DEV and PRE are **local-only Minikube** configurations. The worker mounts `/var/run/docker.sock` from the node, which grants node-level Docker access and is unsuitable for production or untrusted tenants. PRE retains this limitation; it is not a production-safe image builder. The node must expose a Docker socket. Each namespace has its own `generator-worker-artifacts-pvc` mounted at `/var/lib/generator-worker`; workspace is `/var/lib/generator-worker/manifests`.

Kafka (`kafka:9092`), MinIO (`http://minio:9000`) and the canonical API (`http://generator-api-service/api/generator/v1`, service port 80) resolve within the selected namespace. PRE uses profile `pre` and OAuth client `scaffoldops-generator-worker` in realm `scaffoldops-pre`, with token endpoint `http://keycloak-pre.security.svc.cluster.local:8080/realms/scaffoldops-pre/protocol/openid-connect/token`. Infrastructure provisioning, realms, clients, services, storage and secret values are managed separately.

The legacy `generator-api:8081` alias remains a selector-backed ClusterIP service targeting API pods on port 8080. Do not convert it to ExternalName: DNS aliases cannot translate port 8081 to canonical port 80. The worker does not manage or overwrite the actual `generator-api-service` service.

Offline deployment regression checks (requires `kubectl` and Python `PyYAML`):

```bash
python3 scripts/validate-deployment.py
```

## GitHub Actions

- `pr-checks.yml`: Maven verification and tests for PRs to `develop`/`main` and feature branch pushes.
- `develop-pipeline.yml`: verify, test, build/push image, deploy to `scaffoldops-dev`.
- `main-pipeline.yml`: corresponding PRE pipeline targeting `scaffoldops-pre`.
- `deploy-k8s.yml`: reusable `workflow_call` deployment, selects context `minikube` and waits for rollout; it is not manually dispatchable.

Jobs use self-hosted runners. PRE needs additional infrastructure; see [delivery guide](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/delivery.md).

## Troubleshooting

Check `kubectl config current-context`, pods, events and component logs before restarting. For `ImagePullBackOff`, check the image/tag and namespace-local registry secret. `docker-hub-credentials` is Opaque application configuration and must never be used as `imagePullSecrets`. WSL runners need Linux Docker, daemon access, and a readable kubeconfig; a WindowsApps Docker shim can cause EACCES. Port conflicts require changing the local side of the port-forward. See [operations](https://github.com/ScaffoldOps/scaffoldops-docs/blob/main/docs/operations.md) for commands and lifecycle diagnostics.
