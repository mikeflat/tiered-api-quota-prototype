# Spec Delta

## MODIFIED Requirements

### Requirement: Gateway runs as a container image
The gateway SHALL be buildable into a container image from `/gateway` with only Docker on the host, and the container SHALL start the gateway as a non-root user, serve `/actuator/health` on the published port and report itself healthy through a container healthcheck on that endpoint. The image SHALL read the tenants file from `/config/tenants.json` by default (overridable with `TENANTS_FILE`) and SHALL NOT contain a tenants file of its own, so the file is mounted at run time.

#### Scenario: Build and run with Docker only
- **WHEN** an operator runs `docker build` in `/gateway` and then runs the image with port `8080` published and the repository's `config/tenants.json` mounted read-only at `/config/tenants.json`
- **THEN** `GET http://localhost:8080/actuator/health` returns `200` with `"status":"UP"`
- **AND** the container's health status becomes `healthy`

#### Scenario: Container process is not root
- **WHEN** the gateway container is running
- **THEN** the Java process runs as a non-root user

#### Scenario: Run without the tenants file
- **WHEN** an operator runs the image without mounting a tenants file
- **THEN** the container exits with a non-zero code
- **AND** its log names `/config/tenants.json` as missing

## ADDED Requirements

### Requirement: Startup fails fast on invalid configuration
The gateway SHALL validate the tenants file and the quota settings before it accepts HTTP requests. When either is invalid it SHALL exit with a non-zero code without ever answering `GET /actuator/health`, so container health and start scripts report the failure instead of a running gateway with no tenants.

#### Scenario: Invalid tenants file
- **WHEN** the gateway starts with a tenants file that contains an unknown tier
- **THEN** the process exits with a non-zero code
- **AND** no port answers `GET /actuator/health` for it

#### Scenario: Invalid quota setting
- **WHEN** the gateway starts with `QUOTA_PERIOD_SECONDS=0`
- **THEN** the process exits with a non-zero code
- **AND** no port answers `GET /actuator/health` for it

#### Scenario: Valid configuration without Redis
- **WHEN** the gateway starts with a valid tenants file and default quota settings and no Redis server is available
- **THEN** startup succeeds and `GET /actuator/health` returns `200` with `"status":"UP"`
