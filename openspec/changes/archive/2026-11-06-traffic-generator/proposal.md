# Proposal

## Why

The gateway enforces quotas and the admin API exposes live usage, but nothing sends traffic yet: the generator is a skeleton that only logs a startup line. Without continuous, uneven traffic the dashboard (backlog items 12–15) has nothing to show and the demo scenarios S1–S3, S7 and S8 cannot be observed. This change turns the skeleton into the real traffic emulator.

Backlog mapping: item 11 of docs/04 backlog.md (Phase 4: Traffic), change id `traffic-generator`.

## What Changes

- **Tenants from the shared file**: the generator reads tenant ids from `tenants.json` (the same file the gateway reads), path from `TENANTS_FILE`; a missing, unreadable or invalid file stops it at startup with a clear message (FR-24).
- **Traffic shape in its own config**: a new `generator/traffic.json` holds the total rate (default 200 req/s), a default weight and per-tenant weights by id; path from `TRAFFIC_CONFIG_FILE`. Invalid values, or weights for ids that are not in `tenants.json`, stop the generator at startup (FR-24, NFR-7, NFR-8).
- **Rate-paced, weighted traffic**: requests are paced to the total rate (catching up after timer drift, but never bursting more than one second's worth) and each request picks a tenant by weighted random choice; it sends `GET /api/v1/resource` with `X-Tenant-Id` over keep-alive `fetch` (FR-23, D12).
- **Default shape produces all three states**: starting weights are chosen so that, at the default rate and the default limits (100 / 1,000 / 10,000 per 60 s), some tenants stay ok, some end the period near and some go over (FR-25). Fine-tuning is left to backlog item 16 `calibration-and-demo`.
- **Survives gateway outages**: each request has a timeout; at most a bounded number of requests are in flight, and ticks beyond that are counted as skipped instead of queued. Connection errors, timeouts and non-200/429 statuses are counted as errors and never crash the process (FR-26, S8).
- **Periodic summary**: every 10 s one log line with sent, 200, 429, errors, skipped and the last error reason for the interval; a final summary on stop (FR-26).
- **Startup line** now names the gateway, tenant count and rate instead of `traffic=off (skeleton)`.
- **Docker compose**: the generator gets `config/tenants.json` mounted at `/config/tenants.json` and `generator/traffic.json` mounted at `/app/traffic.json`, so the traffic shape changes with a generator restart and no rebuild. The image bakes in `traffic.json` and defaults `TENANTS_FILE=/config/tenants.json`.
- Existing generator code is brought in line with the project rules (no code comments, snake_case test names).

## Capabilities

### New Capabilities
- `traffic-generation`: what traffic the generator sends and how — tenant source, traffic-shape configuration and its validation, rate pacing, weighted tenant choice, request format, outcome classification, behaviour while the gateway is unavailable, bounded in-flight requests and the periodic summary.

### Modified Capabilities
- `generator-runtime`: the startup line no longer says no traffic is sent and names tenants and rate; the "skeleton sends no traffic" scenario is removed; running the image now needs a tenants file mounted; stopping logs a final summary and abandons in-flight requests; the test-run scenario covers the traffic tests.
- `stack-runtime`: the shared tenants file is now also mounted into the generator, and the generator's traffic config is mounted from the repository.

## Impact

- **Generator code** (`/generator`, flat modules, exempt from Clean Architecture): `src/main.ts` (traffic loop, summary timer, shutdown), `src/config.ts` (env settings), new flat modules for tenants and traffic config loading, pacing, weighted pick, request sending and summary counting, each with Vitest tests. No new runtime dependencies.
- **New file**: `generator/traffic.json`. **Changed**: `generator/Dockerfile`, `generator/.env.example`, `generator/README.md`, `docker-compose.yml`.
- **Gateway, dashboard, Redis, scripts**: untouched. The generator only calls the existing `GET /api/v1/resource`.

Requirements and decisions covered: FR-23, FR-24, FR-25 (starting values), FR-26, NFR-7, NFR-8 (generator traffic shape), NFR-9 (generator with Vitest), S1, S2, S8 (generator side), D12.

### Out of scope (docs/02 §6)
- Controlling the generator from the dashboard or at runtime (no HTTP endpoint, no hot reload of the config).
- Load testing or proving the production rate of about 4,200 req/s.
- Calls to any endpoint other than `GET /api/v1/resource`; authentication of the generator.
- Calibrating tier limits and weights against observed demo runs, and the demo README (backlog item 16).
- Time-varying traffic patterns (bursts, ramps, day/night curves).
