# Design

## Context

The gateway is a five-module Gradle build (`:domain`, `:app`, `:adapters:api`, `:adapters:infra`, `:bootstrap`) whose inner modules are still empty. Beans are declared with `@Bean` methods in the per-module configuration classes in `bootstrap/.../config`; only `bootstrap` is component-scanned and only `bootstrap` and the adapters may use Spring. ArchUnit (`ArchitectureTest`) enforces the inward dependency rule, and `archunit.properties` sets `failOnEmptyShould=false` while modules are empty. `:adapters:infra` is meant for communication with other services (Redis); this change adds a sixth module, `:adapters:persistence`, for data-source implementations (D-8). The gateway `Dockerfile` copies every module's `build.gradle.kts` before the sources, so a new module must be listed there too. Spring Boot is 4.1, so Jackson is Jackson 3 (`tools.jackson.*`). Gradle runs tests with the module directory as working directory, so `../config/tenants.json` does not resolve from `gateway/bootstrap`.

Motivation and scope: see proposal.md. Behaviour: see the `tenant-registry`, `quota-configuration`, `gateway-runtime` and `stack-runtime` deltas.

## Goals / Non-Goals

**Goals:**
- A framework-free domain model (tiers, tenants, registry, quota settings) that items 3–10 use directly.
- One startup path: load and validate everything before the web server listens; any problem stops the process with a readable message.
- Tests for every layer that run with `./gradlew test` and need no Docker or Redis.

**Non-Goals:**
- Filtering, pagination and tier updates on the registry (items 8 and 10). The registry is only made ready for them (concurrent map, immutable `Tenant` values).
- Status classification (ok / near / over) and the period index. Only the near-limit *boundary* is defined here, because it is the only rule the threshold setting has.
- An HTTP surface for tenants or tiers.

## Decisions

### D-1 Layer placement

| Layer (module / package) | Type | Role |
|---|---|---|
| domain `com.mikeflat.gateway.domain.tenant` | `Tier` (enum `BRONZE`, `SILVER`, `GOLD`) | Canonical display name (`Bronze`…), `Tier.parse(String)` case-insensitive, throws `IllegalArgumentException` for unknown values |
| domain `…domain.tenant` | `Tenant` (record `long id`, `name`, `tier`) | Rejects an `id` below 1, a null/blank `name`, a null `tier`; `withTier(Tier)` for item 8 |
| domain `…domain.tenant` | `TenantRegistry` (final class) | Built from a collection of tenants; rejects an empty collection and duplicate ids. `Optional<Tenant> find(long id)`, `List<Tenant> listByName()` (name case-insensitive, then id numerically), `Map<Tier, Integer> countByTier()`, `size()`. Backed by a `ConcurrentHashMap<Long, Tenant>`; listing sorts a copy |
| domain `com.mikeflat.gateway.domain.quota` | `QuotaSettings` (record `Duration period`, `Map<Tier, Long> limits`, `int nearThresholdPercent`) | Invariants: period ≥ 1 s and whole seconds, a limit ≥ 1 for every tier (copied into an `EnumMap`), threshold 1..99. `limitOf(Tier)`, `periodSeconds()`, `nearBoundary(long limit)` = `ceil(limit × T / 100)` in exact integer arithmetic (`Math.multiplyExact`, then `(x + 99) / 100`) |
| domain `…domain.tenant` | `TenantRepository` (domain port, interface) | `List<Tenant> findAll()` and `String description()` (e.g. the file path, for messages and logs). Declared next to the `Tenant` aggregate (D-9) |
| domain `…domain.tenant` | `TenantLoadException` (unchecked) | Carries the source description and the problem; thrown by repository implementations and by the use case |
| app `com.mikeflat.gateway.app.tenant` | `LoadTenantRegistry` (use case) | Calls the repository once, builds `TenantRegistry`, wraps a domain `IllegalArgumentException` (empty list, duplicate id) into `TenantLoadException` with the source description |
| adapter out `com.mikeflat.gateway.adapters.persistence.file` | `JsonFileTenantRepository` (implements `TenantRepository`) | Reads the file at a `Path` with Jackson data binding into a private record `TenantEntry(Long id, String name, String tier)` through a private strict `JsonMapper` (unknown properties ignored, no scalar coercion: no string or float to integer, no number or boolean to string), validates each entry, maps it to `Tenant` via `Tier.parse`, and raises `TenantLoadException` naming the file, the entry (index and id when known) and the field or value |
| infrastructure `com.mikeflat.gateway.bootstrap.config` | `QuotaProperties` (`@ConfigurationProperties("quota")`, `@Validated` record) | `periodSeconds`, `limits.bronze/silver/gold`, `nearThresholdPercent`; Bean Validation `@NotNull`, `@Min`, `@Max`; `toSettings()` builds `QuotaSettings` |
| infrastructure `…bootstrap.config` | `TenantsProperties` (`@ConfigurationProperties("tenants")`, `@Validated` record) | `@NotNull Path file` |
| infrastructure `…bootstrap.config` | `DomainConfiguration`, `AppConfiguration`, `PersistenceConfiguration` (new, imported by `GatewayApplication`) | `QuotaSettings` bean (domain), `LoadTenantRegistry` + `TenantRegistry` beans (app), `TenantRepository` bean (persistence); each logs its startup line. `InfraConfiguration` stays for Redis and is unchanged |
| infrastructure `…bootstrap` | `TenantLoadFailureAnalyzer` (`FailureAnalyzer`, registered in `META-INF/spring.factories`) | Turns a `TenantLoadException` cause into Boot's "APPLICATION FAILED TO START" report with the problem and the action "check `TENANTS_FILE`" |

Ports used: `TenantRepository` only (a domain port, used by the `LoadTenantRegistry` use case). No clock or Redis port is introduced yet; when they come (items 4–5) they are application ports (D-9).

*Alternative considered:* putting the in-memory `TenantRegistry` itself behind the repository port. Rejected: the registry is plain Java with no I/O, it is the domain's source of truth at request time, and putting it behind a port would force the hot-path lookup through an adapter for no gain. The repository is only the source the registry is loaded from at startup.

### D-2 Validation in two places, messages in one

Quota settings are validated by Bean Validation on `QuotaProperties`, because Boot's binding failure report already names the property (`quota.limits.silver`), the rejected value and its origin, and non-numbers (`lots`) fail at binding with the same kind of report. `QuotaSettings` re-checks the same invariants as domain invariants (unit-tested), so no other caller can build invalid settings. Tenant file errors are reported by the adapter with file-specific context; domain checks on `Tenant`/`TenantRegistry` are the backstop and are wrapped by the use case.

*Alternative:* validate only in the domain. Rejected: the domain must not know property or environment variable names, so operator messages would lose them.

### D-3 Configuration keys and defaults

`application.yaml` gains:

```yaml
tenants:
  file: ${TENANTS_FILE:../config/tenants.json}
quota:
  period-seconds: ${QUOTA_PERIOD_SECONDS:60}
  limits:
    bronze: ${QUOTA_LIMIT_BRONZE:100}
    silver: ${QUOTA_LIMIT_SILVER:1000}
    gold: ${QUOTA_LIMIT_GOLD:10000}
  near-threshold-percent: ${QUOTA_NEAR_THRESHOLD_PERCENT:80}
```

Explicit placeholders follow the existing `${SERVER_PORT:8080}` style and make the environment variable names part of the documented contract instead of relying on relaxed binding. The default `../config/tenants.json` fits running the jar from `gateway/` (as the README does). The `bootRun` and `test` Gradle tasks in `bootstrap/build.gradle.kts` set `TENANTS_FILE` to the absolute path of the repository's `config/tenants.json`, so `./gradlew test` exercises the real seed. The Dockerfile sets `ENV TENANTS_FILE=/config/tenants.json`.

The period is configured in whole seconds (not a `Duration` string such as `60s`) because the period index formula and Redis TTLs work in seconds and a single number is simpler to override from compose.

### D-4 Tenants file format

A top-level JSON array of `{ "id", "name", "tier" }` objects, the shape the generator will read in item 11 (its weights live in its own config, keyed by id). Ids are positive integers (JSON numbers, Java `long`), numbered 1..15 in alphabetical order of the names; names differ from ids so the name-contains and exact-id filters (item 10) are visibly different:

| Tier | Tenants (`id` — name) |
|---|---|
| Bronze (8) | `1` — Acme Corp, `2` — Bluebird Analytics, `3` — Cobalt Labs, `4` — Driftwood Travel, `5` — Ember Foods, `6` — Fjord Logistics, `7` — Granite Insurance, `8` — Harbor Health |
| Silver (5) | `9` — Ironwood Retail, `10` — Juniper Media, `11` — Kestrel Payments, `12` — Lumen Energy, `13` — Meridian Bank |
| Gold (2) | `14` — Nimbus Cloud, `15` — Orbit Telecom |

Extra fields are ignored so the file can grow (e.g. a comment) without breaking the gateway. Types are strict: an id must be a JSON whole number of at least 1 that fits a `long` (`"1"`, `1.5`, `true` or `0` are rejected), and `name` and `tier` must be JSON strings. The `X-Tenant-Id` guard (item 3) parses the header into a `long` before the registry lookup.

*Alternative considered:* string slugs (`acme`) as ids. Replaced by integer ids during implementation at the user's request; integer ids map directly to a future database key.

### D-5 Fail fast before the web server starts

All beans are eager singletons, and Boot starts the embedded server only after the context has finished refreshing. A failing `TenantRegistry` or `QuotaSettings` bean therefore aborts startup before any port is bound, and `SpringApplication` exits non-zero. This is what the start scripts and the container healthcheck already detect. No lazy initialisation is enabled.

*Alternative:* start with an empty registry and report an error in health. Rejected: every business request would then get 400 with no visible reason, and health would have to depend on configuration.

### D-6 Container and compose

- `gateway/Dockerfile`: add `ENV TENANTS_FILE=/config/tenants.json`; no file is copied into the image (the build context is `/gateway`, and one shared file avoids drift with the generator).
- `docker-compose.yml`, gateway service: `volumes: ["./config/tenants.json:/config/tenants.json:ro"]` and `environment` entries `QUOTA_PERIOD_SECONDS: ${QUOTA_PERIOD_SECONDS:-60}` etc. Compose interpolation with defaults is used because an empty pass-through value would reach Spring as an empty string and fail binding. The defaults repeat those in `application.yaml`; a comment in both places points at the other.
- Root `.env.example` and `gateway/.env.example` document the five quota variables and `TENANTS_FILE`.

### D-7 Tests per layer

| Layer | Test | Tools |
|---|---|---|
| domain | `TierTest`, `TenantTest`, `TenantRegistryTest` (lookup, case-sensitivity, ordering, duplicates, empty, counts), `QuotaSettingsTest` (invariants, `limitOf`, `nearBoundary` incl. rounding and overflow). `TenantRepository` is an interface and needs no test of its own | JUnit 5, AssertJ, no Spring |
| app | `LoadTenantRegistryTest` with a hand-written fake `TenantRepository` (success, empty, duplicate wrapped with the source description, repository exception passed through) | JUnit 5, AssertJ |
| adapter out (persistence) | `JsonFileTenantRepositoryTest` in `:adapters:persistence` with `@TempDir` files: valid file, lower-case tier, extra field, missing file, malformed JSON, not an array, entry not an object, missing/blank fields, id that is not a positive whole number (`"1"`, `1.5`, `0`), non-string name, unknown tier | JUnit 5, AssertJ |
| infrastructure | `QuotaPropertiesBindingTest` (`ApplicationContextRunner`: defaults, overrides, `0`, `-5`, `100`, `lots`), `TenantRegistryStartupTest` (`@SpringBootTest` with the real seed: 15 tenants, 8/5/2, unique ids, startup log lines via `OutputCaptureExtension`), `StartupFailureTest` (`SpringApplication.run` with a bad tenants file / bad period throws, and the failure analyzer's description names the file), `ArchitectureTest` extended with the persistence rules (D-8), existing `HealthEndpointTest`/`ServerPortTest` unchanged | Spring Boot test, ArchUnit |

Testcontainers is not needed: nothing in this change touches Redis.

### D-8 Separate persistence module for data sources

A new Gradle module `:adapters:persistence` (package `com.mikeflat.gateway.adapters.persistence`) holds every implementation of a repository port, one subpackage per storage technology: `…persistence.file` now (`JsonFileTenantRepository`), `…persistence.db` later when tenants move to a database (production direction of D2/D11). `:adapters:infra` keeps only communication with other services (Redis now) and gets no Jackson dependency and no `config` package.

- **Dependencies:** `:domain` and Jackson only (`implementation`, BOM-managed). It implements domain ports (D-9), so it needs nothing from `:app`. No Spring and no Redis, so the file adapter is plain Java; a future `db` package adds its own dependencies to this module.
- **Wiring:** `PersistenceConfiguration` in `bootstrap/.../config` declares the `TenantRepository` bean. Switching to a database means adding the `db` package and changing or adding a bean method there (for example selected by a `tenants.source` property). Domain, use cases, `:adapters:api` and `:adapters:infra` stay untouched. No selection property is added now, since there is only one source.
- **Dependency rules** (Gradle declarations plus new `ArchitectureTest` rules): persistence must not depend on `adapters.api` or `adapters.infra`, and neither of them may depend on persistence. The existing domain/app/bootstrap rules already cover it.
- **Build:** `settings.gradle.kts` includes `adapters:persistence`; the `Dockerfile` copies `adapters/persistence/build.gradle.kts` in its dependency layer.

*Alternative:* keep data sources in `:adapters:infra` under a `config` subpackage. Rejected: it mixes storage with service communication, and a database adapter would then pull JDBC/JPA next to the Redis client in one module.

### D-9 Repository ports live in the domain (DDD / hexagonal)

The gateway follows a DDD / hexagonal port rule, now recorded in `openspec/config.yaml` and, by task 5.4, in the gateway README:

| Port kind | Declared in | Implemented in | Examples |
|---|---|---|---|
| Repository of domain objects (domain port) | domain package of the aggregate | `:adapters:persistence` | `TenantRepository` (this change) |
| Other outbound port (application port) | `:app`, next to the use case that needs it | `:adapters:infra` | Redis usage recording (item 4), metrics reads (item 9), clock |
| Inbound port | `:app` use cases | called by `:adapters:api` | `LoadTenantRegistry` (startup), tenant lookup (item 3), tier change (item 8) |

So `TenantRepository` and `TenantLoadException` sit in `domain.tenant` beside `Tenant` and `TenantRegistry`; `LoadTenantRegistry` stays in `:app` and orchestrates the load. `:adapters:persistence` depends on `:domain` only, and `ArchitectureTest` still holds (the domain depends on nothing outward). A future `db` implementation still only touches `:adapters:persistence` and `PersistenceConfiguration`.

*Alternative:* all outbound ports in `:app` (plain Clean Architecture, the earlier draft). Rejected by the project's chosen DDD / hexagonal style: repositories are part of the domain model's vocabulary, and keeping them beside their aggregate lets later domain services depend on them without reaching into the application layer.

## Risks / Trade-offs

- [Running the gateway image without a mount now fails] → Documented in the README and the `gateway-runtime` delta; compose and the scripts mount the file, so the normal path is unaffected. The failure message names `/config/tenants.json`.
- [Defaults duplicated in `application.yaml` and `docker-compose.yml`] → Comments cross-reference both; `QuotaPropertiesBindingTest` pins the `application.yaml` defaults, and calibration (item 16) is the only planned change to them.
- [`bootRun`/`test` depend on a path outside `gateway/`] → Computed from `rootDir` in Gradle, so it works from any working directory; the Docker build does not run tests and does not need the file.
- [Registry uses a `ConcurrentHashMap` although nothing writes yet] → Intentional, so item 8 can update tiers without changing lookup code or adding locks on the request path.
- [ArchUnit `failOnEmptyShould=false` still needed] → `:adapters:api` and `:adapters:infra` stay empty until items 3 and 4; the setting is removed once they have classes, not here.
- [A sixth module adds build overhead for one class] → Accepted for a clear storage boundary; the module is small and has no Spring.

## Migration Plan

No data to migrate. Anyone running the gateway image by hand adds `-v "$PWD/config/tenants.json:/config/tenants.json:ro"`; the README's Docker section is updated with that command. Rollback is reverting the change; no external state is created.
