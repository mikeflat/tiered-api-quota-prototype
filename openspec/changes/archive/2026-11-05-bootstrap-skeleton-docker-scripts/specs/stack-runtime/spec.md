# Spec Delta

## Purpose

Defines how Redis, the gateway, the dashboard and the generator are wired into one local stack and how an operator builds, starts and stops the whole stack, or Redis, the gateway and the generator individually, with scripts in `/scripts`, so demos (Redis outage, gateway outage) and later features can rely on one way to run the prototype.

## ADDED Requirements

### Requirement: One command builds and starts the whole stack
Running the `start` script SHALL build the gateway, dashboard and generator images from the repository sources, start Redis, the gateway, the dashboard and the generator, return only after each service is running (and healthy where it has a healthcheck), print the dashboard and gateway URLs, and exit with code `0` (FR-27).

#### Scenario: Clean start
- **WHEN** an operator with only Docker installed runs `scripts/start.sh` (or `scripts\start.cmd` on Windows) on a fresh checkout
- **THEN** the script exits with code `0`
- **AND** four containers are running: `redis`, `gateway`, `dashboard`, `generator`
- **AND** the output names `http://localhost:3000` and `http://localhost:8080`

#### Scenario: Sources changed since the last start
- **WHEN** a gateway, dashboard or generator source file has changed and the operator runs the `start` script again
- **THEN** the affected image is rebuilt and the running container is replaced by one from the new image

#### Scenario: Start while already running
- **WHEN** the stack is already running and the operator runs the `start` script with no source changes
- **THEN** the script exits with code `0` and all four services are still running

### Requirement: Services are wired together inside the stack
Inside the stack the gateway SHALL be configured to reach Redis by its service name, the dashboard SHALL forward `/admin/**` and `/actuator/health` to the gateway, and the generator SHALL target the gateway, all without host-specific settings.

#### Scenario: Gateway health through the dashboard origin
- **WHEN** the stack has been started with the `start` script
- **THEN** `GET http://localhost:3000/actuator/health` returns `200` with `"status":"UP"`
- **AND** `GET http://localhost:3000/` returns the dashboard app shell with `200`

#### Scenario: Gateway configured with the Redis service
- **WHEN** the stack is running
- **THEN** the gateway container's Redis host setting is `redis` and port is `6379`
- **AND** `redis-cli ping` inside the Redis container answers `PONG`

#### Scenario: Generator targets the gateway service
- **WHEN** the stack is running
- **THEN** the generator log shows its startup line naming `http://gateway:8080`

### Requirement: Host ports are local and configurable
The stack SHALL publish the dashboard on host port `3000`, the gateway on `8080` and Redis on `6379` by default, bound to the loopback interface only, and each host port SHALL be overridable through an environment variable without editing files under version control (NFR-8).

#### Scenario: Default ports
- **WHEN** the stack is started without port overrides
- **THEN** `GET http://localhost:8080/actuator/health` returns `200` with `"status":"UP"`
- **AND** a TCP connection to `localhost:6379` reaches Redis

#### Scenario: Port override
- **WHEN** the host port for the gateway is set to `9090` in the environment or in a root `.env` file and the stack is started
- **THEN** `GET http://localhost:9090/actuator/health` returns `200`
- **AND** nothing in the stack listens on host port `8080`

#### Scenario: Not reachable from other machines
- **WHEN** the stack is running
- **THEN** the published ports are bound to `127.0.0.1` only

#### Scenario: Host port already taken
- **WHEN** another process already listens on the gateway's host port and the `start` script runs
- **THEN** the script exits with a non-zero code and the output names the port conflict

### Requirement: Whole stack stops with one command
Running the `stop` script SHALL stop and remove all stack containers and the stack network, keep the built images, and exit with code `0`, also when the stack is not running (FR-28).

#### Scenario: Stop a running stack
- **WHEN** the stack is running and the operator runs `scripts/stop.sh` (or `scripts\stop.cmd`)
- **THEN** the script exits with code `0`
- **AND** no container of the stack remains, running or stopped

#### Scenario: Stop when nothing is running
- **WHEN** no container of the stack exists and the operator runs the `stop` script
- **THEN** the script exits with code `0`

### Requirement: Each component starts and stops on its own
For each of `redis`, `gateway` and `generator`, a `start-<component>` and a `stop-<component>` script SHALL start or stop only that service and leave every other service as it was (FR-28; deviation: the dashboard has no component scripts and runs only with the whole stack). `start-<component>` SHALL return after the service is running (and healthy where it has a healthcheck); both SHALL exit with code `0` when the service is already in the requested state.

#### Scenario: Redis outage while the gateway keeps serving
- **WHEN** the stack is running and the operator runs `stop-redis`
- **THEN** the Redis container is stopped and the gateway, dashboard and generator keep running
- **AND** `GET http://localhost:8080/actuator/health` still returns `200` with `"status":"UP"` (NFR-4)

#### Scenario: Redis comes back
- **WHEN** Redis was stopped with `stop-redis` and the operator runs `start-redis`
- **THEN** Redis is running and answers `PONG`, and no other container was restarted

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
- **THEN** only the gateway container is started, and it reports `"status":"UP"` without Redis

#### Scenario: Generator stopped and started
- **WHEN** the operator runs `stop-generator` and then `start-generator`
- **THEN** the generator container exits with code `0` on stop and is running again after start, while the other services are unaffected

#### Scenario: Stop a component that is not running
- **WHEN** the generator is not running and the operator runs `stop-generator`
- **THEN** the script exits with code `0`

### Requirement: Every script exists for POSIX shells and Windows cmd
Every script SHALL be provided as `scripts/<name>.sh` for POSIX `sh` and `scripts/<name>.cmd` for Windows `cmd.exe`, with the same names, behavior, output and exit codes, and both forms SHALL keep working after a checkout on Linux, macOS or Windows.

#### Scenario: Matching script sets
- **WHEN** the `/scripts` folder is listed
- **THEN** for each of `start`, `stop`, `start-redis`, `stop-redis`, `start-gateway`, `stop-gateway`, `start-generator`, `stop-generator` there is exactly one `.sh` and one `.cmd` file

#### Scenario: Windows checkout runs the sh scripts
- **WHEN** the repository is checked out on Windows with default Git line-ending conversion and a `.sh` script is run with `sh` (Git Bash or WSL)
- **THEN** the script runs without line-ending errors

#### Scenario: cmd script run from Explorer or another folder
- **WHEN** an operator runs `scripts\start-redis.cmd` from a different current directory
- **THEN** it acts on the repository's stack exactly like `scripts/start-redis.sh`

### Requirement: Scripts work from any working directory
Each script SHALL locate the repository's compose definition relative to its own location, so it behaves the same whatever the current working directory is.

#### Scenario: Run from outside the repository
- **WHEN** an operator runs `/path/to/repo/scripts/start.sh` with the home directory as the current directory
- **THEN** the stack defined in the repository's `docker-compose.yml` is started

### Requirement: Scripts fail visibly
A script SHALL exit with a non-zero code and print an error message when Docker or docker compose is not available, the Docker engine is not running, or the underlying compose command fails; it SHALL NOT report success in these cases.

#### Scenario: Docker engine not running
- **WHEN** the Docker engine is stopped and the operator runs any script
- **THEN** the script exits with a non-zero code
- **AND** the output says that Docker is not available

#### Scenario: Image build fails
- **WHEN** a gateway source file does not compile and the operator runs the `start` script
- **THEN** the script exits with a non-zero code and the build error is shown
