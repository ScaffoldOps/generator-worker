# generator-worker

Asynchronous worker service for **ScaffoldOps** responsible for processing generation jobs after they are accepted by `generator-api`.

## Purpose

`generator-worker` is part of the ScaffoldOps platform, whose goal is to automate the generation and deployment of standardized microservices on Kubernetes. In the platform architecture, `generator-api` acts as the synchronous entrypoint, while `generator-worker` handles the long-running generation work asynchronously. :contentReference[oaicite:0]{index=0}

This service exists to keep request handling separate from execution. It should consume generation work published by `generator-api`, generate the requested microservice from a template, and update lifecycle status as the work progresses. This separation keeps the API simple and moves retries, failures, and longer-running processing outside the request/response path. :contentReference[oaicite:1]{index=1}

## Responsibilities

`generator-worker` should own:

- consuming generation jobs or events produced by `generator-api`
- loading the generation request data needed for processing
- generating the microservice project from the selected template
- producing the standard project structure and supporting files
- updating lifecycle status during the generation phase
- reporting success or failure back through persisted lifecycle state

## What it should not do

`generator-worker` should not:

- expose the main public API for request submission
- validate incoming client HTTP requests
- act as the system of record for generation requests
- replace `generator-api` as the lifecycle query endpoint

If deployment is later separated into its own service, `generator-worker` should also not own Kubernetes deployment. For the MVP, that concern may remain combined temporarily if needed, but the intended architecture keeps generation and deployment conceptually distinct. :contentReference[oaicite:2]{index=2}

## Role in the platform flow

A typical ScaffoldOps flow is:

1. the client sends a generation request to `generator-api`
2. `generator-api` validates and persists the request
3. `generator-api` assigns an initial status such as `RECEIVED`
4. `generator-api` publishes a generation job or event
5. `generator-worker` consumes that job
6. `generator-worker` updates the request to a generation-in-progress state
7. `generator-worker` generates the project from the requested template
8. `generator-worker` updates the request to `GENERATED` or `FAILED`

This makes `generator-worker` the execution component for the generation phase, while `generator-api` remains the public lifecycle/status entrypoint for clients. :contentReference[oaicite:3]{index=3}

## Lifecycle ownership

The lifecycle is shared across services by stage:

- `generator-api` creates the request and sets the initial state
- `generator-worker` owns the generation-stage transitions
- a future `deployment-worker` can own deployment-stage transitions

Example lifecycle progression:

- `RECEIVED`
- `GENERATING`
- `GENERATED`
- `DEPLOYING`
- `DEPLOYED`
- `FAILED`

For `generator-worker`, the relevant transitions are typically:

- `RECEIVED -> GENERATING`
- `GENERATING -> GENERATED`
- `GENERATING -> FAILED`

## Expected inputs

`generator-worker` is expected to process generation work created by `generator-api`.

That work should represent a generation request containing, at minimum, fields such as:

- request id
- service name
- template
- deployment target
- optional capabilities such as database, REST API, security, and messaging

The initial request contract in ScaffoldOps was defined around a declarative payload similar to:

```json
{
  "name": "billing-service",
  "template": "spring-boot-hexagonal",
  "database": true,
  "restApi": true,
  "security": true,
  "messaging": false,
  "deploymentTarget": "kubernetes"
}
