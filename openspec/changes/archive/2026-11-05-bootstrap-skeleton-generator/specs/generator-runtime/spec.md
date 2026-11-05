# Spec Delta

## Purpose

Defines how the traffic generator is started without a build step, configured with the gateway address, stopped cleanly, run as a container and tested, so the traffic behaviour (backlog item 11) and the start/stop scripts can rely on a stable runtime surface.

## ADDED Requirements

### Requirement: Generator starts without a build step
A developer SHALL be able to start the generator from TypeScript sources with one documented command after installing dependencies, with no compile or bundle step, using Node.js LTS (D12). On start the generator SHALL log one line that names the gateway address in use and states that no traffic is sent yet.

#### Scenario: Start command runs the sources directly
- **WHEN** a developer runs the documented start command in `/generator` on a clean checkout after installing dependencies
- **THEN** the process starts without producing any compiled output files
- **AND** it logs a startup line containing the gateway address `http://localhost:8080`

#### Scenario: Skeleton sends no traffic
- **WHEN** the generator is running
- **THEN** it sends no request to the gateway
- **AND** it keeps running whether or not a gateway is reachable

### Requirement: Gateway address comes from configuration
The generator SHALL read the gateway base address from the `GATEWAY_URL` environment variable, defaulting to `http://localhost:8080` when it is unset or empty, so the target changes without code changes (NFR-8). A value that is not an absolute `http` or `https` URL SHALL stop the generator at startup with a non-zero exit code and a message naming the variable and the rejected value.

#### Scenario: Default address
- **WHEN** the generator starts with `GATEWAY_URL` unset
- **THEN** the startup line names `http://localhost:8080`

#### Scenario: Address override
- **WHEN** the generator starts with `GATEWAY_URL=http://gateway:9090`
- **THEN** the startup line names `http://gateway:9090`

#### Scenario: Invalid address fails fast
- **WHEN** the generator starts with `GATEWAY_URL=not a url` or `GATEWAY_URL=ftp://gateway`
- **THEN** the process exits within a few seconds with a non-zero exit code
- **AND** the error output names `GATEWAY_URL` and the rejected value

### Requirement: Generator stops cleanly on a signal
The generator SHALL keep running until it receives `SIGTERM` or `SIGINT`, and on either signal SHALL log a stop line and exit with code `0` within a few seconds.

#### Scenario: Stopped by the container runtime
- **WHEN** the running generator container is stopped with `docker stop`
- **THEN** the container exits with code `0` before Docker's stop timeout forces a kill
- **AND** its log ends with the stop line

### Requirement: Generator runs as a container image
The generator SHALL be buildable into a container image from `/generator` with only Docker on the host. The container SHALL run the sources directly on Node.js LTS as a non-root user, listen on no port, and default the gateway address to `http://gateway:8080` (the gateway's service name in the stack), overridable with `GATEWAY_URL`.

#### Scenario: Build and run with Docker only
- **WHEN** an operator runs `docker build` in `/generator` and runs the image without extra settings and with no gateway running
- **THEN** the container stays running
- **AND** its log shows the startup line naming `http://gateway:8080`

#### Scenario: Process is not root
- **WHEN** the generator container is running
- **THEN** the Node.js process runs as a non-root user

### Requirement: Sources stay runnable without compilation
The documented type-check command SHALL fail when the sources do not type-check or use TypeScript syntax that Node.js cannot run by stripping types (for example `enum` or parameter properties).

#### Scenario: Clean type-check
- **WHEN** the documented type-check command runs on the unchanged skeleton
- **THEN** it exits successfully

#### Scenario: Non-strippable syntax is rejected
- **WHEN** a source file declares an `enum` and the type-check command runs
- **THEN** the command exits with a non-zero status

### Requirement: Generator has an automated test run
The generator SHALL have one documented command that runs its Vitest tests headlessly and exits non-zero on any failure (NFR-9).

#### Scenario: Skeleton tests pass
- **WHEN** a developer runs the documented test command on the unchanged skeleton
- **THEN** the configuration tests and the startup smoke test run and pass, and the command exits successfully
