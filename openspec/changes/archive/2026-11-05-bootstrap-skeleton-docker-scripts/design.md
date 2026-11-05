# Design

## Context

The three component slices left each service as a self-contained Docker image with defaults chosen for this stack:

| Service | Image source | Listens | Healthcheck | Stack-relevant defaults |
|---|---|---|---|---|
| gateway | `gateway/Dockerfile` (JDK build → JRE, non-root) | `8080` | `curl /actuator/health` (start period 30 s) | `SPRING_DATA_REDIS_HOST=localhost`, `SPRING_DATA_REDIS_PORT=6379`; Redis is not used yet and not part of health |
| dashboard | `dashboard/Dockerfile` (Vite build → nginx-unprivileged) | `8080` | `wget /` (independent of the gateway) | `GATEWAY_UPSTREAM=gateway:8080`, `NGINX_RESOLVER=127.0.0.11` (Docker DNS), per-request resolve so it survives gateway restarts |
| generator | `generator/Dockerfile` (Node, no build, non-root) | none | none ("running" is health) | `GATEWAY_URL=http://gateway:8080`; exits `0` on `SIGTERM` |
| redis | official image | `6379` | — (added here) | — |

The repository has no `docker-compose.yml`, no root `.gitattributes`, and `/scripts` holds only `.gitkeep`. `gateway/.gitattributes` already forces LF for `gradlew` and `*.sh` inside `/gateway` only. Host has Docker with compose v2 (`docker compose`). See proposal.md for motivation.

Clean Architecture does not apply: this change adds no gateway or dashboard code, only runtime wiring and scripts.

## Goals / Non-Goals

**Goals:**
- One compose file is the single source of truth for how the stack runs; every script is a thin wrapper around one `docker compose` command.
- Component scripts give demo-quality outages: stopping one service never stops, restarts or rebuilds another.
- `.sh` and `.cmd` forms behave identically and are trivially reviewable side by side.

**Non-Goals:**
- Running components outside Docker from the scripts (each component README covers that).
- Production concerns: resource limits, restart policies, secrets, Redis persistence, multiple gateway instances.
- Mounting `tenants.json` (no file yet; added by `tenant-registry-and-config`).

## Decisions

### D-S1. Compose file at the repo root, fixed project name
`docker-compose.yml` at the root (architecture §10) with top-level `name: tiered-quota`, services `redis`, `gateway`, `dashboard`, `generator`, one default network. Service names are the DNS names the images already expect (`gateway`), so the dashboard and generator need **no** environment overrides. Images are tagged `quota-gateway:local`, `quota-dashboard:local`, `quota-generator:local`, the names already used in the component READMEs, so manual `docker run` examples and the stack share images.

*Why a fixed name:* without it compose derives the project from the folder name, so a clone in another folder would be a different project and `stop` would miss containers. *Alternative:* `container_name` per service — rejected, it blocks two clones running side by side and is not needed for the scripts.

### D-S2. Configuration hand-off
- gateway: `SPRING_DATA_REDIS_HOST=redis`, `SPRING_DATA_REDIS_PORT=6379`. The gateway does not connect to Redis yet; this is the wiring later items use.
- dashboard, generator: image defaults (`gateway:8080`, `http://gateway:8080`).
- No `tenants.json` volume yet.

### D-S3. No health-gated dependencies
`depends_on` is used only for start order (`gateway` after `redis`; `dashboard` and `generator` after `gateway`) with `condition: service_started` and `required: false`, never `service_healthy`.

*Why:* NFR-4 / D10 — the gateway must start without Redis; the dashboard (nginx per-request resolve) and the generator are built to survive a missing gateway. Health gating would make `start-gateway` wait on Redis and couple outages. Component scripts additionally pass `--no-deps` (D-S6), so start order never pulls in other services.

### D-S4. Host ports: loopback only, overridable
```
redis:     "127.0.0.1:${REDIS_PORT:-6379}:6379"
gateway:   "127.0.0.1:${GATEWAY_PORT:-8080}:8080"
dashboard: "127.0.0.1:${DASHBOARD_PORT:-3000}:8080"
```
The prototype has no authentication (requirements §6), so nothing is exposed beyond the laptop. Redis is published so an IDE-run gateway (`./gradlew bootRun`) and `redis-cli` can use the stack's Redis. Overrides come from the shell environment or a root `.env` (compose reads it automatically; `.env` is already git-ignored, `!.env.example` is kept). A root `.env.example` documents the three variables. The `start` script prints the effective URLs using the same variables with the same defaults.

### D-S5. Redis service
Official `redis` alpine image pinned to a current 8.x minor at implementation time (`redis:8.<minor>-alpine`). Command `redis-server --save "" --appendonly no`: counters are period-scoped and expire (architecture §2.2), so persistence buys nothing and would leave `dump.rdb` files. Healthcheck `redis-cli ping` every 5 s. No volume. `restart: "no"` on all services, so a stopped component stays stopped until its start script runs.

### D-S6. Script set and compose commands
All scripts live flat in `/scripts`; shared logic in `/scripts/lib/`.

| Script | Command (after `lib` prepends `--project-directory <root> -f <root>/docker-compose.yml`) |
|---|---|
| `start` | `up -d --build --wait --wait-timeout 180 --remove-orphans`, then print URLs |
| `stop` | `down --remove-orphans` (keeps images; removes containers and network) |
| `start-<svc>` (`redis`, `gateway`, `generator`) | `up -d --no-deps --wait --wait-timeout 180 <svc>` |
| `stop-<svc>` (`redis`, `gateway`, `generator`) | `stop <svc>` |

- `--wait` makes "returned = running, and healthy where a healthcheck exists" true (gateway, dashboard, Redis healthy; generator running). If a container exits or stays unhealthy, compose exits non-zero and so does the script.
- `start-<svc>` does **not** pass `--build`: restarting the gateway in a demo should take seconds, not a Gradle build. Compose still builds an image that does not exist yet. To pick up source changes, run `start` (rebuilds everything, recreates only changed services). Component scripts forward extra arguments to compose, so `start-gateway.sh --build` works when wanted.
- `stop-<svc>` uses `stop` (not `rm`): `start-<svc>` then resumes the same container quickly; `stop` cleans everything up. Stopping an already stopped or absent service is a no-op with exit `0` in compose.
- `stop-generator` relies on the generator exiting `0` on `SIGTERM` (generator-runtime spec) within compose's default 10 s grace period.

The dashboard has no component scripts (proposal: deviation from FR-28); it runs only with `start` / `stop`.

*Alternative:* one parametrised script (`component.sh start gateway`) — rejected: FR-28 and the backlog describe per-component scripts, and named files are what a demo operator (and backlog item 16's README) will use.

### D-S7. Shared helper per shell
- `scripts/lib/compose.sh`: resolves the repo root from the calling script's location (`$(cd "$(dirname "$0")/.." && pwd)`), checks `command -v docker`, `docker compose version` and `docker info` (engine running), prints `error: Docker is not available: <reason>` to stderr and exits `1` on failure, otherwise runs `docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" "$@"` and returns its exit code. Each `.sh` script is `#!/bin/sh`, `set -eu`, sources the helper, calls one function. POSIX `sh` only (no bash-isms), so it runs under dash, macOS sh and Git Bash.
- `scripts/lib/compose.cmd`: same steps in batch: root from `%~dp0..`, `where docker >nul 2>&1`, `docker compose version >nul 2>&1`, `docker info >nul 2>&1`, then `docker compose --project-directory "%ROOT%" -f "%ROOT%\docker-compose.yml" %*`, `exit /b %ERRORLEVEL%`. Each `.cmd` script is `@echo off`, `setlocal`, `call "%~dp0lib\compose.cmd" ...`, `exit /b %ERRORLEVEL%`. No `pause`, so scripts stay usable from other scripts and CI.
- Both helpers print the same messages, so the two forms produce the same output.

*Why a helper:* ten scripts × two shells would otherwise duplicate the Docker checks twenty times. The helper lives in `lib/`, so `/scripts` itself lists exactly the ten script names per form.

### D-S8. Line endings
Root `.gitattributes`: `*.sh text eol=lf`, `*.cmd text eol=crlf`, `*.bat text eol=crlf`. A CRLF `.sh` fails under `sh` (`$'\r': command not found`); an LF `.cmd` can misparse labels and `call` in `cmd.exe`. `gateway/.gitattributes` stays as is (consistent). `.sh` files are committed with the executable bit (`git update-index --chmod=+x`), so `./scripts/start.sh` works on Linux/macOS as well as `sh scripts/start.sh`.

### D-S9. Documentation
A short `scripts/README.md`: prerequisites, the script table, port variables, and the two outage demos (stop Redis / stop gateway). The full demo walkthrough stays with backlog item 16.

## Risks / Trade-offs

- [First `start` is slow (Gradle + npm downloads in image builds)] → BuildKit cache mounts already exist in the Dockerfiles; `start-<svc>` never rebuilds, so demos stay fast after the first run.
- [`--wait` needs a recent compose v2 (`--wait-timeout` since 2.21)] → helper checks `docker compose version` exists; README states the minimum version. Host has v5.4.
- [Gateway start period is 30 s, so `start` / `start-gateway` can block up to ~40 s] → acceptable; the 180 s wait timeout bounds it and the failure is reported, not hidden.
- [Host port already used (e.g. a local Redis on 6379)] → compose fails, script exits non-zero with compose's message; the port variables in `.env` resolve it.
- [Redis restarted by `stop-redis`/`start-redis` comes back empty] → intended for the prototype: nothing is counted during an outage (requirements §7), counters are period-scoped.
- [Stopped containers kept by `stop-<svc>` keep an old image after a rebuild] → `start` recreates services whose image changed; `stop` removes all containers.
- [`.cmd` and `.sh` drift apart over time] → one helper per shell, each script one line of logic; the spec's matching-set scenario is checked in tasks.

## Migration Plan

Additive only. Rollback: delete `docker-compose.yml`, `.env.example`, `.gitattributes` and `/scripts/*` (restore `scripts/.gitkeep`). No data to migrate.
