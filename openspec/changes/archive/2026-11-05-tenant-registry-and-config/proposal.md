# Proposal

## Why

Backlog item 2, `tenant-registry-and-config` (docs/04 backlog.md). Every later gateway change needs to know who the tenants are and what their limits are: the `X-Tenant-Id` guard (item 3) looks tenants up, the writer (items 4–5, 7) compares `used` with the tenant's current limit and the near threshold, the period length drives the period index and `Retry-After`, and the admin API (items 8–10) lists tenants and tiers. Today the gateway is an empty skeleton with no tenant or quota model, and `config/` holds only a `.gitkeep`.

## What Changes

- Add the seed file `config/tenants.json` with 15 tenants (8 Bronze, 5 Silver, 2 Gold), each with an integer `id`, a `name` and a `tier` (FR-9, NFR-7). It is the single tenant source shared with the generator later (FR-24).
- Add the pure domain model to the gateway: tiers (Bronze, Silver, Gold), tenants, quota settings (period length, limit per tier, near-limit threshold) with their validation rules, and an in-memory, thread-safe tenant registry with lookup by id and listing sorted by name (FR-9, FR-10, architecture `TenantRegistry`, `TierConfig`).
- Add a gateway module `:adapters:persistence` for data-source implementations, with one package per storage technology (`file` now, a `db` package later), so tenants can later be loaded from a database without changing the domain, use cases or other adapters. `:adapters:infra` stays for communication with other services (Redis).
- Load the registry once at startup from `tenants.json` through a `TenantRepository` domain port (DDD / hexagonal: repository interfaces belong to the domain, now a project rule in `openspec/config.yaml`), a loading use case in the application layer and a JSON file repository in `:adapters:persistence`; the file path comes from `TENANTS_FILE` (FR-9).
- Read period length, tier limits and near threshold from `application.yaml`, each overridable by an environment variable, with the prototype defaults 60 s, 100 / 1,000 / 10,000 and 80 % (FR-10, NFR-8, D4).
- Fail fast: the gateway refuses to start, with a message naming the problem, when the tenants file is missing or invalid or the quota settings are invalid. On success it logs one line with the tenant count per tier and the effective quota settings.
- Container and stack: the gateway image expects the tenants file at `/config/tenants.json`; docker compose mounts `config/tenants.json` read-only into the gateway and passes the quota settings through from the environment / root `.env`. **BREAKING** for running the gateway image on its own: `docker run` now needs the tenants file mounted.
- Update `gateway/README.md`, `gateway/.env.example` and the root `.env.example` with the new settings.

No HTTP endpoint changes: the registry and settings are not exposed yet (that comes with items 3 and 8).

## Capabilities

### New Capabilities
- `tenant-registry`: the gateway's in-memory tenant registry — seeding from `tenants.json`, validation of the file, lookup by id, listing sorted by name. Later items add filtering and tier updates to it.
- `quota-configuration`: the static quota settings — period length, limit per tier, near-limit threshold — their defaults, overrides and validation.

### Modified Capabilities
- `gateway-runtime`: the container requirement changes — the image reads the tenants file from `/config/tenants.json` (overridable with `TENANTS_FILE`) and must be run with that file mounted; startup fails fast on invalid tenants or quota configuration.
- `stack-runtime`: service wiring adds the read-only mount of `config/tenants.json` into the gateway and the pass-through of the quota settings.

## Impact

- **Code**: `gateway/domain` (first classes, including the repository port), `gateway/app` (first use case), new `gateway/adapters/persistence` (JSON tenants file repository), `gateway/settings.gradle.kts`, `gateway/bootstrap` (configuration properties, wiring incl. a new `PersistenceConfiguration`, startup log, `application.yaml`, `build.gradle.kts` for `bootRun`/`test` paths, `ArchitectureTest` rules for the new module), `gateway/Dockerfile`, `docker-compose.yml`, `config/tenants.json`, READMEs and `.env.example` files. `gateway/adapters/api`, `gateway/adapters/infra`, the dashboard and the generator are unchanged.
- **Requirements covered**: FR-9, FR-10, NFR-7 (15 seeded tenants), NFR-8 (period, limits and threshold configurable without code changes), groundwork for FR-2, FR-6, FR-11, FR-16. Decisions: D4 (configurable period length), D11 (tier changes later stay in memory; the registry is memory-only), D2 (registry lives in the gateway).
- **Dependencies**: Jackson (version from the Spring Boot BOM) on `:adapters:persistence` for reading JSON. No new runtime services.
- **Tests**: existing Spring Boot tests (`HealthEndpointTest`, `ServerPortTest`) now load the registry at context start, so the Gradle `test` task points them at the real `config/tenants.json`.

## Out of scope

- The `X-Tenant-Id` filter and 400 response (item 3), any use of limits or threshold at request time (items 4–7), the period index / `Retry-After` computation (item 5).
- Tier changes, `GET /admin/tiers`, filters and pagination (items 8–10).
- The generator reading `tenants.json` (item 11) and calibration of limits (item 16).
- Per docs/02 section 6: no database (the persistence module only prepares the place for a future `db` package; no database adapter, driver or service is added), no persisting tier changes across restarts, no editing tier limits at runtime, no authentication, a single gateway instance.
- Reloading `tenants.json` or the quota settings while the gateway runs.
