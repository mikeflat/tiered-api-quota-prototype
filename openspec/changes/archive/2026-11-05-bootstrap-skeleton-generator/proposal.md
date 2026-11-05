# Proposal

## Why

Backlog item 1, `bootstrap-skeleton` (docs/04 backlog.md), asks for an empty gateway, dashboard and generator that each start and answer a trivial request. The gateway and dashboard slices are done (`bootstrap-skeleton-gateway`, `bootstrap-skeleton-dashboard`, archived); this change is the **generator slice**. `/generator` holds only a `.gitkeep` today, and backlog item 11 (`traffic-generator`) and the compose/scripts slice need a runnable, containerised Node.js + TypeScript project to add to.

The generator is a demo traffic emulator, not production code, so it is kept as a small plain script (architecture §2.4: "a single TypeScript script"). It deliberately does **not** follow the project's Clean Architecture rule: no layer folders, no ports, no lint-enforced layering.

The generator is a client, not a server, so it has no inbound request to answer. For this slice "starts and answers a trivial request" is read as: the process starts with one documented command, validates its one setting (the gateway address), logs a startup line, stays up until it is stopped, and shuts down cleanly. It sends **no** traffic yet.

## What Changes

- Initialise `/generator` as a Node.js LTS + TypeScript project run directly by Node's built-in type stripping, with **no build step** (D12, architecture §2.4): npm with a committed `package-lock.json`, no runtime dependencies, dev dependencies only for type-checking and tests.
- Flat layout: `src/main.ts` (entry point: startup, logging, shutdown) and `src/config.ts` (reads the gateway address), with co-located tests.
- Read the gateway base URL from the `GATEWAY_URL` environment variable (default `http://localhost:8080`, image default `http://gateway:8080`), failing fast with a clear message and a non-zero exit code when the value is not a valid `http(s)` URL (NFR-8 groundwork).
- `src/main.ts` logs a startup line naming the gateway URL and stating that no traffic is sent yet, keeps the process alive, and on `SIGTERM` / `SIGINT` logs a stop line and exits with code `0`.
- Type-check with `tsc --noEmit` restricted to syntax Node can strip (`erasableSyntaxOnly`), so code that would only work after a compile step is rejected.
- Set up Vitest (node environment) with tests for the gateway address setting (default, override, invalid value) and a process smoke test for the startup line (NFR-9 groundwork).
- Add a `Dockerfile` (Node LTS alpine, sources copied, no build, non-root `node` user, `CMD ["node", "src/main.ts"]`), `.dockerignore`, `.env.example`, `README.md`; delete the stray `generator/.gitkeep`.

## Capabilities

### New Capabilities
- `generator-runtime`: how the traffic generator is started and operated — one start command without a build step, gateway address from configuration with fail-fast validation, startup/stop logging and clean shutdown on signals, container image and automated tests. Backlog item 11 adds a separate behaviour capability (traffic shape, pacing, outage survival, summaries) on top of it.

### Modified Capabilities
<!-- None: gateway-runtime and dashboard-runtime requirements are unchanged. -->

## Impact

- **Code**: everything under `/generator`. No other folder changes (the root `.gitignore` already covers `node_modules/`, `coverage/`, `.env`).
- **Requirements covered**: the generator part of backlog item 1 ("each service starts"); FR-23 groundwork (own container; it does not send traffic yet); NFR-8 groundwork (gateway address configurable without code changes); NFR-9 groundwork (Vitest wired with first tests); D12 (Node.js generator, TypeScript run directly, no build step); architecture §2.4 and §10 layout.
- **Deviation**: the project context's Clean Architecture rule ("Generator: separate pacing/selection logic from HTTP sending, config loading and logging") is intentionally not applied to the generator, because it is a demo emulator of a few lines.
- **Dependencies (new, generator only, all dev)**: TypeScript, `@types/node`, Vitest — same majors as the dashboard. Runtime image: official Node LTS alpine (same major as the dashboard's build stage).
- **Ports**: none; the generator listens on no port.

## Out of scope

- Sending any traffic, reading `tenants.json` or a traffic-shape config, pacing, weighted selection, retry/outage handling and periodic summaries (FR-23..FR-26, backlog item 11).
- Root `docker-compose.yml`, the Redis service and `/scripts` start/stop scripts (FR-27, FR-28) — another `bootstrap-skeleton` slice. `/config/tenants.json` is seeded by `tenant-registry-and-config`.
- Everything in docs/02 requirements.md section 6, in particular controlling the generator from the dashboard and load testing at production rate (~4,200 req/s).
- Layer folders, ports and a linter for the generator.
- A container healthcheck: the generator has no endpoint to probe; Docker's process state ("running") is its health.
