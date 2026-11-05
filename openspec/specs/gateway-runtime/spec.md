# gateway-runtime Specification

## Purpose

Defines how the quota gateway service starts, reports its own health, exposes its operational endpoints and runs as a container, so operators, the dashboard and scripts can rely on a stable runtime surface.

## Requirements

### Requirement: Gateway reports its health over HTTP
The gateway SHALL answer `GET /actuator/health` with status `200`, a JSON body containing `"status":"UP"`, and no authentication, as soon as it has started.

#### Scenario: Health is UP after startup
- **WHEN** the gateway has started and a client sends `GET /actuator/health` without credentials
- **THEN** the response status is `200`
- **AND** the `Content-Type` is a JSON media type
- **AND** the body contains `"status":"UP"`

### Requirement: Health does not depend on Redis
The gateway's health status SHALL NOT depend on Redis being reachable (NFR-4). In this skeleton the gateway does not connect to Redis at startup and Redis is not part of health; when Redis is later reported as a health component (FR-17, backlog item 6), the gateway's liveness and readiness still must not depend on it.

#### Scenario: Gateway starts and reports UP with no Redis running
- **WHEN** the gateway starts with no Redis server available
- **THEN** startup succeeds
- **AND** `GET /actuator/health` returns `200` with `"status":"UP"`

### Requirement: Only the health actuator endpoint is exposed
The gateway SHALL expose no actuator endpoint over HTTP other than health, and the health response SHALL NOT include component details.

#### Scenario: Environment endpoint is hidden
- **WHEN** a client sends `GET /actuator/env`
- **THEN** the response status is `404`

#### Scenario: Beans endpoint is hidden
- **WHEN** a client sends `GET /actuator/beans`
- **THEN** the response status is `404`

### Requirement: HTTP port is configurable
The gateway SHALL listen on the port given by the `SERVER_PORT` environment variable, and on port `8080` when it is not set (NFR-8: configuration without code changes).

#### Scenario: Default port
- **WHEN** the gateway starts without `SERVER_PORT`
- **THEN** `GET /actuator/health` answers on port `8080`

#### Scenario: Port from environment
- **WHEN** the gateway starts with `SERVER_PORT` set to a free port, for example `9090`
- **THEN** `GET /actuator/health` answers `200` on that port

### Requirement: Gateway runs as a container image
The gateway SHALL be buildable into a container image from `/gateway` with only Docker on the host, and the container SHALL start the gateway as a non-root user, serve `/actuator/health` on the published port and report itself healthy through a container healthcheck on that endpoint.

#### Scenario: Build and run with Docker only
- **WHEN** an operator runs `docker build` in `/gateway` and then runs the image with port `8080` published
- **THEN** `GET http://localhost:8080/actuator/health` returns `200` with `"status":"UP"`
- **AND** the container's health status becomes `healthy`

#### Scenario: Container process is not root
- **WHEN** the gateway container is running
- **THEN** the Java process runs as a non-root user

### Requirement: Unknown paths are not served
Until business and admin endpoints are added by later changes, the gateway SHALL answer any other path with `404` rather than a payload.

#### Scenario: Request to a path that does not exist yet
- **WHEN** a client sends `GET /api/v1/resource` to the skeleton gateway
- **THEN** the response status is `404`
