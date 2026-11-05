# Proposal

## Why

Backlog item 1, `bootstrap-skeleton` (docs/04 backlog.md), asks for docker compose with Redis and scripts in `/scripts` that start and stop the whole stack or each component. The gateway, dashboard and generator slices are done (`bootstrap-skeleton-gateway`, `bootstrap-skeleton-dashboard`, `bootstrap-skeleton-generator`, archived), each with its own Dockerfile, but nothing wires them together yet: there is no `docker-compose.yml` and `/scripts` holds only a `.gitkeep`. This change is the **last slice** of `bootstrap-skeleton`. Later items rely on it: item 6 demos stopping Redis, item 11 adds the generator to the running stack, item 16 documents the demo through the component scripts.

## What Changes

- Add a root `docker-compose.yml` with four services — `redis`, `gateway`, `dashboard`, `generator` — on one compose network, using the existing Dockerfiles in `/gateway`, `/dashboard`, `/generator` and the official Redis image:
  - the gateway is pointed at Redis by service name (`SPRING_DATA_REDIS_HOST=redis`);
  - the dashboard proxies to `gateway:8080` and the generator targets `http://gateway:8080` (their image defaults, D13);
  - host ports published on `127.0.0.1` only and overridable through environment variables: gateway `8080`, dashboard `3000`, Redis `6379`;
  - Redis runs without persistence (counters are period-scoped and expire, architecture §2.2) and has a `redis-cli ping` healthcheck;
  - no service waits for another to be healthy, so the gateway starts without Redis (NFR-4) and the dashboard and generator start without the gateway.
- Add a root `.env.example` documenting the host-port variables.
- Add scripts in `/scripts`, each in two forms with the same name — POSIX `sh` (`.sh`) and Windows `cmd` (`.cmd`):
  - `start` — build all images and start the whole stack, wait until it is up, print the URLs (FR-27);
  - `stop` — stop and remove the whole stack (FR-28);
  - `start-<component>` / `stop-<component>` for `redis`, `gateway`, `generator` — start or stop one service without touching the others (FR-28). The dashboard has no component scripts; it starts and stops only with the whole stack.
  - Scripts work from any working directory and exit non-zero with a clear message when Docker or docker compose is unavailable or the compose command fails.
- Add a root `.gitattributes` that keeps `*.sh` with LF and `*.cmd` with CRLF line endings, so both forms run after a checkout on any OS.
- Remove the stray `scripts/.gitkeep`.

**Deviation (flagged):** FR-28 lists component scripts for Redis, gateway, dashboard and generator. By decision, the dashboard gets none: its outage is not part of any demo scenario (the dashboard is the observer of the Redis and gateway outages), so it is only started and stopped with the whole stack (`start` / `stop`). This change updates FR-28 in docs/02 requirements.md and the `/scripts` line in docs/03 architecture.md §10 to match.

## Capabilities

### New Capabilities
- `stack-runtime`: how the whole prototype is wired and operated as one stack — the compose definition (services, networking, ports, configuration hand-off between services) and the start/stop scripts for the stack and each component in `sh` and `cmd` form.

### Modified Capabilities
<!-- None: gateway-runtime, dashboard-runtime and generator-runtime requirements are unchanged; their images are used as they are. -->

## Impact

- **Files**: `docs/02 requirements.md` (FR-28 wording) and `docs/03 architecture.md` (§10 `/scripts` line); new `docker-compose.yml`, `.env.example`, `.gitattributes` at the repo root; new `/scripts/*.sh` and `/scripts/*.cmd`; `scripts/.gitkeep` removed. No source code under `/gateway`, `/dashboard`, `/generator` changes.
- **Requirements covered**: FR-27 (one command builds and starts Redis, gateway, dashboard and generator), FR-28 (start/stop Redis, gateway and generator individually and stop the whole stack; dashboard deviation above), groundwork for FR-23 ("starts with the stack"), NFR-4 (gateway starts and stays UP without Redis; no compose dependency on Redis health), NFR-8 (host ports configurable without code changes), NFR-7 (laptop-friendly: four containers, no persistence). Decisions: D10 (gateway health independent of Redis), D13 (dashboard and admin API on one origin through nginx). Architecture §1 (four containers) and §10 layout (`docker-compose.yml`, `/scripts`).
- **Dependencies (new)**: official `redis` image (alpine variant). Host requirement: Docker with the compose v2 plugin (`docker compose`); a POSIX shell for `.sh` (Linux, macOS, Git Bash/WSL) or `cmd.exe` for `.cmd`.

## Out of scope

- Mounting `/config/tenants.json` into the gateway and the generator — added by `tenant-registry-and-config` / `traffic-generator` when the file exists.
- Redis being part of gateway health, Redis failure handling (backlog item 6) and any generator traffic (backlog item 11).
- The project README with demo instructions (backlog item 16); this change documents the scripts only in a short `scripts/README.md`.
- A PowerShell form of the scripts, `restart`/`logs`/`status` scripts, and scripts that run components outside Docker.
- Everything in docs/02 requirements.md section 6, in particular multiple gateway instances, Redis clustering or high availability, and controlling the generator from the dashboard.
