## MODIFIED Requirements

### Requirement: Each component starts and stops on its own
For each of `redis`, `gateway` and `generator`, a `start-<component>` and a `stop-<component>` script SHALL start or stop only that service and leave every other service as it was (FR-28; deviation: the dashboard has no component scripts and runs only with the whole stack). `start-<component>` SHALL return after the service is running (and healthy where it has a healthcheck); both SHALL exit with code `0` when the service is already in the requested state.

#### Scenario: Redis outage while the gateway keeps serving
- **WHEN** the stack is running and the operator runs `stop-redis`
- **THEN** the Redis container is stopped and the gateway, dashboard and generator keep running
- **AND** `GET http://localhost:8080/actuator/health/liveness` still returns `200` with `"status":"UP"` and the gateway container stays `healthy` (NFR-4)
- **AND** `GET http://localhost:8080/actuator/health` and `GET http://localhost:3000/actuator/health` return `503` with `"status":"DOWN"` and Redis `DOWN` (FR-17)

#### Scenario: Redis comes back
- **WHEN** Redis was stopped with `stop-redis` and the operator runs `start-redis`
- **THEN** Redis is running and answers `PONG`, and no other container was restarted
- **AND** within a few seconds `GET http://localhost:8080/actuator/health` returns `200` with `"status":"UP"` again

#### Scenario: Gateway outage
- **WHEN** the stack is running and the operator runs `stop-gateway`
- **THEN** the gateway container is stopped and Redis, dashboard and generator keep running
- **AND** `GET http://localhost:3000/` still returns `200`
- **AND** `GET http://localhost:3000/actuator/health` returns a `5xx` status within a few seconds

#### Scenario: Gateway comes back
- **WHEN** the gateway was stopped with `stop-gateway` and the operator runs `start-gateway`
- **THEN** the script returns after the gateway container is healthy
- **AND** `GET http://localhost:3000/actuator/health` returns `200` again without restarting the dashboard

#### Scenario: Component started alone
- **WHEN** no container of the stack exists and the operator runs `start-gateway`
- **THEN** only the gateway container is started, the script returns once the container is healthy, and `GET http://localhost:8080/actuator/health/liveness` reports `"status":"UP"` without Redis

#### Scenario: Generator stopped and started
- **WHEN** the operator runs `stop-generator` and then `start-generator`
- **THEN** the generator container exits with code `0` on stop and is running again after start, while the other services are unaffected

#### Scenario: Stop a component that is not running
- **WHEN** the generator is not running and the operator runs `stop-generator`
- **THEN** the script exits with code `0`
