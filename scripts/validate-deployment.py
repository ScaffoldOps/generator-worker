"""Offline manifest/workflow regression checks. Requires kubectl and PyYAML."""

import os
import pathlib
import subprocess
import tempfile

import yaml


ROOT = pathlib.Path(__file__).resolve().parents[1]


def load(path):
    return yaml.safe_load((ROOT / path).read_text())


deploy_steps = load(".github/workflows/deploy-k8s.yml")["jobs"]["deploy"]["steps"]
deploy_script = next(step["run"] for step in deploy_steps if step["name"] == "Deploy application")
# Stop before the first cluster operation; exercise the exact workflow rendering code.
render_script = deploy_script.split("kubectl config use-context minikube", 1)[0]
render_script += 'cat "${render_dir}/deployment.yaml"\n'
sha = "0123456789abcdef0123456789abcdef01234567"


def render_workflow(profile, namespace, environment, tag):
    with tempfile.TemporaryDirectory(prefix="generator-worker-check-") as temp:
        return subprocess.run(
            ["bash", "-c", render_script], cwd=ROOT, text=True, capture_output=True,
            env={**os.environ, "RUNNER_TEMP": temp, "SPRING_PROFILE": profile,
                 "NAMESPACE": namespace, "ENVIRONMENT_NAME": environment,
                 "IMAGE_NAME": "victodomvar/scaffoldops-generator-worker", "IMAGE_VERSION": tag},
        )


for profile, branch, environment in (("dev", "develop", "DEV"), ("pre", "main", "PRE")):
    namespace = f"scaffoldops-{profile}"
    rendered = subprocess.check_output(
        ["kubectl", "kustomize", f"k8s/overlays/{profile}"], cwd=ROOT, text=True
    )
    resources = list(yaml.safe_load_all(rendered))
    assert len(resources) == 4
    assert all(item["metadata"]["namespace"] == namespace for item in resources)
    assert all(item["kind"] != "Secret" for item in resources)
    other = "pre" if profile == "dev" else "dev"
    assert f"scaffoldops-{other}" not in rendered
    assert f"keycloak-{other}" not in rendered
    by_name = {item["metadata"]["name"]: item for item in resources}
    assert "generator-api-service" not in by_name, "Canonical API service is owned by generator-api"
    alias = by_name["generator-api"]["spec"]
    assert alias["type"] == "ClusterIP"
    assert alias["selector"] == {"app": "generator-api"}
    assert alias["ports"] == [{"protocol": "TCP", "port": 8081, "targetPort": 8080}]
    pod = by_name["generator-worker"]["spec"]["template"]["spec"]
    assert pod["imagePullSecrets"] == [{"name": "docker-hub-pull-secret"}]
    container = pod["containers"][0]
    env = {item["name"]: item for item in container["env"]}
    expected = {
        "SPRING_PROFILES_ACTIVE": profile,
        "SPRING_KAFKA_BOOTSTRAP_SERVERS": "kafka:9092",
        "GENERATOR_MINIO_ENDPOINT": "http://minio:9000",
        "GENERATOR_API_BASE_URL": "http://generator-api-service/api/generator/v1",
        "GENERATOR_API_AUTH_MODE": "client-credentials",
        "GENERATOR_API_LIFECYCLE_HTTP_ENABLED": "true",
        "GENERATOR_API_CLIENT_ID": "scaffoldops-generator-worker",
        "GENERATOR_API_TOKEN_URL": (
            f"http://keycloak-{profile}.security.svc.cluster.local:8080/realms/"
            f"{namespace}/protocol/openid-connect/token"
        ),
        "GENERATOR_DOCKER_BUILD_ENABLED": "true",
        "GENERATOR_DOCKER_PUSH_ENABLED": "true",
    }
    for name, value in expected.items():
        assert env[name]["value"] == value, name
    for name, secret, key in (
        ("GENERATOR_API_CLIENT_SECRET", "generator-api-worker-client", "client-secret"),
        ("GENERATOR_MINIO_ACCESS_KEY", "generator-worker-minio", "access-key"),
        ("GENERATOR_MINIO_SECRET_KEY", "generator-worker-minio", "secret-key"),
        ("DOCKER_USERNAME", "docker-hub-credentials", "DOCKER_USERNAME"),
        ("DOCKER_PASSWORD", "docker-hub-credentials", "DOCKER_PASSWORD"),
    ):
        assert "value" not in env[name]
        assert env[name]["valueFrom"]["secretKeyRef"] == {"name": secret, "key": key}
    volumes = {item["name"]: item for item in pod["volumes"]}
    assert volumes["docker-socket"]["hostPath"] == {"path": "/var/run/docker.sock", "type": "Socket"}
    assert volumes["generator-worker-data"]["persistentVolumeClaim"] == {
        "claimName": "generator-worker-artifacts-pvc"
    }
    assert by_name["generator-worker-artifacts-pvc"]["kind"] == "PersistentVolumeClaim"
    for probe, path in (
        ("startupProbe", "liveness"), ("livenessProbe", "liveness"), ("readinessProbe", "readiness")
    ):
        assert container[probe]["httpGet"] == {"path": f"/actuator/health/{path}", "port": 8080}
    deploy = load(f".github/workflows/{branch}-pipeline.yml")["jobs"]["deploy"]
    assert deploy["with"] == {
        "namespace": namespace,
        "environment_name": environment,
        "spring_profile": profile,
        "image_version": "${{ needs.docker_build.outputs.commit_sha }}",
    }
    assert {"docker_build", "docker_push"}.issubset(deploy["needs"])
    assert load(f"src/main/resources/application-{profile}.yml")["app"]["lifecycle"]["base-url"] == (
        "${GENERATOR_API_BASE_URL:http://generator-api-service/api/generator/v1}"
    )
    workflow_result = render_workflow(profile, namespace, environment, sha)
    assert workflow_result.returncode == 0, workflow_result.stderr
    workflow_resources = list(yaml.safe_load_all(workflow_result.stdout))
    expected_resources = yaml.safe_load_all(rendered)
    for item in expected_resources:
        if item["kind"] == "Deployment":
            item["spec"]["template"]["spec"]["containers"][0]["image"] = (
                f"victodomvar/scaffoldops-generator-worker:{sha}"
            )
        assert item in workflow_resources, "Workflow must only override the image tag"
    print(f"PASS: {environment} manifests, namespace isolation, secret refs, alias, probes and pipeline")

assert render_workflow("pre", "scaffoldops-dev", "PRE", sha).returncode != 0
assert render_workflow("pre", "scaffoldops-pre", "PRE", "latest").returncode != 0
print("PASS: workflow renders full SHA atomically and rejects mismatched environments/mutable tags")
