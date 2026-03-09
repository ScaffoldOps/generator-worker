# Generator Worker Architecture

## Current scope
This repository currently provides the service shell for the asynchronous generation worker.

Included now:
- Spring Boot application bootstrap
- Hexagonal package layout
- Kafka consumer skeleton for generation-requested events
- Placeholder lifecycle update adapter
- Placeholder generation adapter
- Docker, CI/CD, and Kubernetes deployment baseline

## Intended flow
1. `generator-api` publishes a `generation-requested` event.
2. `generator-worker` consumes the event through Kafka.
3. The application service marks the request as `GENERATING`.
4. A future generation adapter performs real scaffold generation.
5. The worker reports `GENERATED` or `FAILED` back through a future lifecycle integration.

## Intentional placeholders
- No real project generation engine yet
- No persistence layer in this service yet
- No HTTP controller surface for public request handling
- No deployment-worker behavior
