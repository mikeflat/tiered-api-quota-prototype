# Spec Delta

## MODIFIED Requirements

### Requirement: Generator stops cleanly on a signal
The generator SHALL keep running until it receives `SIGTERM` or `SIGINT`. On either signal it SHALL stop starting requests, abandon requests still in flight, log a final summary of the unfinished interval and a stop line, and exit with code `0` within a few seconds.

#### Scenario: Stopped by the container runtime
- **WHEN** the running generator container is stopped with `docker stop`
- **THEN** the container exits with code `0` before Docker's stop timeout forces a kill
- **AND** its log ends with a summary line followed by the stop line

#### Scenario: Stopped while the gateway hangs
- **WHEN** the generator receives `SIGINT` while requests are in flight to a gateway that does not answer
- **THEN** it exits with code `0` within a few seconds without waiting for the request timeout

### Requirement: Generator runs as a container image
The generator SHALL be buildable into a container image from `/generator` with only Docker on the host. The container SHALL run the sources directly on Node.js LTS as a non-root user, listen on no port, contain the default traffic config, read tenants from `/config/tenants.json` by default and default the gateway address to `http://gateway:8080` (the gateway's service name in the stack), overridable with `GATEWAY_URL`, `TENANTS_FILE` and `TRAFFIC_CONFIG_FILE`.

#### Scenario: Build and run with Docker only
- **WHEN** an operator runs `docker build` in `/generator` and runs the image with the repository's `config/tenants.json` mounted at `/config/tenants.json` and no gateway running
- **THEN** the container stays running
- **AND** its log shows the startup line naming `http://gateway:8080` and `tenants=15`, followed by summaries with errors

#### Scenario: Run without a tenants file
- **WHEN** the image is run without a file at `/config/tenants.json`
- **THEN** the container exits with a non-zero code and its log names `/config/tenants.json`

#### Scenario: Process is not root
- **WHEN** the generator container is running
- **THEN** the Node.js process runs as a non-root user

## REMOVED Requirements

### Requirement: Generator starts without a build step
**Reason**: Its startup line had to state that no traffic is sent, and its "Skeleton sends no traffic" scenario contradicts the traffic behaviour this change adds.
**Migration**: Replaced by "Generator starts from sources and sends traffic" below, which keeps the no-build-step start contract and changes only the startup line.

### Requirement: Generator has an automated test run
**Reason**: Its only scenario describes the skeleton's test set ("unchanged skeleton"), which no longer exists once traffic is implemented.
**Migration**: Replaced by "Generator tests run headlessly" below, which keeps the same command contract (NFR-9) and covers the traffic tests.

## ADDED Requirements

### Requirement: Generator starts from sources and sends traffic
A developer SHALL be able to start the generator from TypeScript sources with one documented command after installing dependencies, with no compile or bundle step, using Node.js LTS (D12). On start the generator SHALL log one line that names the gateway address in use, the number of tenants loaded and the total rate, and SHALL then start sending traffic.

#### Scenario: Start command runs the sources directly
- **WHEN** a developer runs the documented start command in `/generator` on a clean checkout after installing dependencies
- **THEN** the process starts without producing any compiled output files
- **AND** it logs a startup line containing the gateway address `http://localhost:8080`, `tenants=15` and the configured rate

#### Scenario: Starts with no gateway reachable
- **WHEN** the generator is started while no gateway is reachable
- **THEN** it logs the startup line and keeps running, counting the failed requests as errors

### Requirement: Generator tests run headlessly
The generator SHALL have one documented command that runs its Vitest tests headlessly and exits non-zero on any failure (NFR-9). The tests SHALL run without a gateway, Docker or network access beyond the local machine.

#### Scenario: Tests pass
- **WHEN** a developer runs the documented test command
- **THEN** the configuration, tenants and traffic config loading, pacing, weighted pick, outcome classification, summary and startup/shutdown tests run and pass, and the command exits successfully

#### Scenario: A failing test fails the run
- **WHEN** any generator test fails
- **THEN** the test command exits with a non-zero status
