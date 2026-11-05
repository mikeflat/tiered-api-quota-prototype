## RENAMED Requirements

- FROM: `### Requirement: Health does not depend on Redis`
- TO: `### Requirement: Liveness and readiness do not depend on Redis`

## ADDED Requirements

### Requirement: Health reports Redis as a component
`GET /actuator/health` SHALL report Redis as a component named `redis` with status `UP` or `DOWN` (FR-17). The overall status SHALL be `DOWN` with HTTP `503` when Redis is unreachable and `UP` with `200` when it is reachable. The Redis check SHALL answer within the short Redis command timeout, so a hung Redis never holds the health call (D10). Recovery SHALL need no restart.

#### Scenario: Redis reachable
- **WHEN** Redis is reachable and a client sends `GET /actuator/health`
- **THEN** the response status is `200`
- **AND** the body has `"status":"UP"` and `components.redis.status` `UP`

#### Scenario: Redis unreachable
- **WHEN** no Redis server is reachable at the configured address and a client sends `GET /actuator/health`
- **THEN** the response status is `503`
- **AND** the body is JSON with `"status":"DOWN"` and `components.redis.status` `DOWN`

#### Scenario: Redis hangs
- **WHEN** Redis accepts connections but does not answer (for example a paused container) and a client sends `GET /actuator/health`
- **THEN** the response arrives within 2 seconds with status `503` and `components.redis.status` `DOWN`

#### Scenario: Redis stops mid-run and returns
- **WHEN** the gateway runs with Redis reachable, Redis becomes unreachable, and later becomes reachable again
- **THEN** `GET /actuator/health` reports `UP`, then `DOWN` with Redis `DOWN` during the outage, then `UP` again, without restarting the gateway

#### Scenario: Startup does not check Redis
- **WHEN** the gateway starts with no Redis server available
- **THEN** startup succeeds and no Redis connection is attempted until a health call or a recorded event needs one

## MODIFIED Requirements

### Requirement: Gateway reports its health over HTTP
The gateway SHALL answer `GET /actuator/health` with a JSON body and no authentication, as soon as it has started. With Redis reachable the status SHALL be `200` with `"status":"UP"`; with Redis unreachable it is `503` with `"status":"DOWN"` (see "Health reports Redis as a component").

#### Scenario: Health is UP after startup
- **WHEN** the gateway has started with Redis reachable and a client sends `GET /actuator/health` without credentials
- **THEN** the response status is `200`
- **AND** the `Content-Type` is a JSON media type
- **AND** the body contains `"status":"UP"`

#### Scenario: Health answers without Redis
- **WHEN** the gateway has started with no Redis server available and a client sends `GET /actuator/health` without credentials
- **THEN** the response status is `503`
- **AND** the `Content-Type` is a JSON media type
- **AND** the body contains `"status":"DOWN"`

### Requirement: Liveness and readiness do not depend on Redis
The gateway SHALL expose `GET /actuator/health/liveness` and `GET /actuator/health/readiness`, and neither SHALL depend on Redis (NFR-4, D10): both answer `200` with `"status":"UP"` once the gateway has started, whether Redis is reachable, unreachable or hanging. Redis SHALL NOT be a component of either probe.

#### Scenario: Gateway starts and reports UP with no Redis running
- **WHEN** the gateway starts with no Redis server available
- **THEN** startup succeeds
- **AND** `GET /actuator/health/liveness` returns `200` with `"status":"UP"`
- **AND** `GET /actuator/health/readiness` returns `200` with `"status":"UP"`

#### Scenario: Probes stay UP during a Redis outage
- **WHEN** the gateway runs with Redis reachable and Redis then becomes unreachable or stops answering
- **THEN** `GET /actuator/health` reports `DOWN`
- **AND** `GET /actuator/health/liveness` and `GET /actuator/health/readiness` still return `200` with `"status":"UP"`, each within 1 second

#### Scenario: Probes do not list Redis
- **WHEN** a client sends `GET /actuator/health/liveness` or `GET /actuator/health/readiness`
- **THEN** the body has no `redis` component

### Requirement: Only the health actuator endpoint is exposed
The gateway SHALL expose no actuator endpoint over HTTP other than health and its paths (`/actuator/health`, its probe groups and its component paths). The health responses SHALL show component statuses only, without component details such as Redis host, port, version or error messages.

#### Scenario: Environment endpoint is hidden
- **WHEN** a client sends `GET /actuator/env`
- **THEN** the response status is `404`

#### Scenario: Beans endpoint is hidden
- **WHEN** a client sends `GET /actuator/beans`
- **THEN** the response status is `404`

#### Scenario: Redis component without details
- **WHEN** a client sends `GET /actuator/health` with Redis reachable or unreachable
- **THEN** `components.redis` contains only `status`
- **AND** the body contains neither the Redis host nor the Redis version nor an error message

### Requirement: Gateway runs as a container image
The gateway SHALL be buildable into a container image from `/gateway` with only Docker on the host, and the container SHALL start the gateway as a non-root user, serve `/actuator/health` on the published port and report itself healthy through a container healthcheck on `/actuator/health/liveness`, so a Redis outage never marks the container unhealthy. The image SHALL read the tenants file from `/config/tenants.json` by default (overridable with `TENANTS_FILE`) and SHALL NOT contain a tenants file of its own, so the file is mounted at run time.

#### Scenario: Build and run with Docker only
- **WHEN** an operator runs `docker build` in `/gateway` and then runs the image with port `8080` published, the repository's `config/tenants.json` mounted read-only at `/config/tenants.json`, and no Redis
- **THEN** `GET http://localhost:8080/actuator/health/liveness` returns `200` with `"status":"UP"`
- **AND** `GET http://localhost:8080/actuator/health` returns `503` with Redis `DOWN`
- **AND** the container's health status becomes `healthy`

#### Scenario: Container stays healthy while Redis is down
- **WHEN** the gateway container is healthy and Redis then stops
- **THEN** the container's health status stays `healthy`

#### Scenario: Container process is not root
- **WHEN** the gateway container is running
- **THEN** the Java process runs as a non-root user

#### Scenario: Run without the tenants file
- **WHEN** an operator runs the image without mounting a tenants file
- **THEN** the container exits with a non-zero code
- **AND** its log names `/config/tenants.json` as missing

### Requirement: Unknown paths are not served
The gateway SHALL answer any path it does not define with `404` rather than a payload. Defined paths are `/actuator/health` and its probe and component paths, and `GET /api/v1/resource`; admin endpoints are added by later changes. Under `/api/` the tenant guard runs first, so an unknown path there returns `404` only for a request with a known `X-Tenant-Id` and `400` otherwise.

#### Scenario: Request to a path that does not exist yet
- **WHEN** a client sends `GET /admin/snapshot` to the gateway before the admin API exists
- **THEN** the response status is `404`

#### Scenario: Unknown business path with a known tenant
- **WHEN** a client sends `GET /api/v2/resource` with the id of a known tenant in `X-Tenant-Id`
- **THEN** the response status is `404`

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
- **THEN** startup succeeds and `GET /actuator/health/liveness` returns `200` with `"status":"UP"`
