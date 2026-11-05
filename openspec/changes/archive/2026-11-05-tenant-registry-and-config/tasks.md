# Tasks

All verification runs through the component's test runner (`./gradlew test` in `/gateway`) or static commands. No service, container or stack is started (project test execution rule).

## 1. Seed file and build setup

- [x] 1.1 Create `config/tenants.json` with the 15 tenants from design D-4 (8 Bronze, 5 Silver, 2 Gold) and delete `config/.gitkeep`; verify with `node -e` (or `jq`) that it parses as an array of 15 objects with unique ids and the 8/5/2 tier split
- [x] 1.2 Create the `:adapters:persistence` module (design D-8): include `adapters:persistence` in `settings.gradle.kts`; add `adapters/persistence/build.gradle.kts` depending on `:domain` only (with a header comment: implementations of domain repository ports, no Spring, must not depend on `:app`, `:adapters:api` or `:adapters:infra`); create the package directory `com/mikeflat/gateway/adapters/persistence` with a `.gitkeep`; add `implementation(project(":adapters:persistence"))` to `:bootstrap`; add `COPY adapters/persistence/build.gradle.kts adapters/persistence/` to the Dockerfile's dependency layer; verify `./gradlew projects` lists `:adapters:persistence` and `./gradlew build -x test` succeeds
- [x] 1.3 Add `testImplementation(libs.assertj.core)` to `:domain`, `:app` and `:adapters:persistence`; add `jackson-databind` (`tools.jackson.core:jackson-databind`, BOM-managed) to `libs.versions.toml` and `implementation` of it (with the BOM platform) to `:adapters:persistence`; add `spring-boot-starter-validation` to `:bootstrap`; verify `./gradlew build -x test` succeeds
- [x] 1.4 Extend `ArchitectureTest` with a `PERSISTENCE` package and rules: persistence does not depend on `app`, `adapters.api` or `adapters.infra`, and neither adapter module depends on persistence (design D-8, D-9); verify `./gradlew :bootstrap:test --tests '*ArchitectureTest'` passes
- [x] 1.5 In `bootstrap/build.gradle.kts`, set `TENANTS_FILE` to the absolute path of the repository's `config/tenants.json` (from `rootDir`) for the `bootRun` and `test` tasks (design D-3); verify `./gradlew :bootstrap:test` still passes (no code reads it yet)

## 2. Domain: tiers, tenants, registry, quota settings

- [x] 2.1 Write `TierTest` first, then `Tier` in `domain.tenant` (canonical names, case-insensitive `parse`, unknown value rejected with the value in the message); verify `./gradlew :domain:test` passes
- [x] 2.2 Write `TenantTest`, then the `Tenant` record (null/blank `id`/`name`, null `tier` rejected naming the field; `withTier`); verify `./gradlew :domain:test` passes
- [x] 2.3 Write `TenantRegistryTest` (exact and case-sensitive lookup, blank/null id → empty, ordering by name case-insensitively then id, empty collection and duplicate id rejected naming the id, `countByTier`, `size`), then `TenantRegistry` backed by a `ConcurrentHashMap` (design D-1); verify `./gradlew :domain:test` passes
- [x] 2.4 Write `QuotaSettingsTest` (period ≥ 1 s, limit ≥ 1 for every tier and missing tier rejected, threshold 1..99, `limitOf`, `nearBoundary` for 100/80 → 80, 7/80 → 6, 10000/80 → 8000, overflow rejected), then the `QuotaSettings` record in `domain.quota`; verify `./gradlew :domain:test` passes and `ArchitectureTest` still passes

- [x] 2.5 Add the `TenantRepository` domain port and `TenantLoadException` in `domain.tenant` (design D-1, D-9); verify `./gradlew :domain:compileJava` succeeds and `ArchitectureTest` still passes

## 3. Application: load use case

- [x] 3.1 Write `LoadTenantRegistryTest` with a hand-written fake `TenantRepository` (success, empty list and duplicate id wrapped into `TenantLoadException` naming the source description, repository exception passed through unchanged), then the `LoadTenantRegistry` use case; verify `./gradlew :app:test` passes

## 4. Persistence adapter: JSON tenants file

- [x] 4.1 Write `JsonFileTenantRepositoryTest` in `:adapters:persistence` with `@TempDir` files for: valid file, lower-case tier, extra field ignored, missing file (message names the path), malformed JSON, top level not an array, entry not an object, missing `name`, blank `id`, unknown tier `Platinum` (message names entry id and value); verify the tests fail before the adapter exists
- [x] 4.2 Implement `JsonFileTenantRepository` in `adapters.persistence.file` with a private Jackson `JsonMapper` and tree walking (design D-1, D-4, D-8), and delete the module's `.gitkeep`; verify `./gradlew :adapters:persistence:test` passes

## 5. Wiring, configuration and startup behaviour

- [x] 5.1 Add the `tenants` and `quota` blocks to `application.yaml` with the environment placeholders from design D-3 (comment pointing at the compose defaults); add `QuotaProperties` and `TenantsProperties` (`@Validated` records) and enable them; write `QuotaPropertiesBindingTest` with `ApplicationContextRunner` (defaults, `QUOTA_PERIOD_SECONDS=86400`, `QUOTA_LIMIT_GOLD=500` + `QUOTA_NEAR_THRESHOLD_PERCENT=90`, and failures for `0`, `-5`, `100`, `0` threshold, `lots`, each naming the property and value); verify `./gradlew :bootstrap:test` passes
- [x] 5.2 Declare the beans: `QuotaSettings` in `DomainConfiguration`, `LoadTenantRegistry` and `TenantRegistry` in `AppConfiguration`, `JsonFileTenantRepository` as the `TenantRepository` bean in a new `PersistenceConfiguration` imported by `GatewayApplication` (`InfraConfiguration` unchanged, design D-8), with the two startup log lines (tenants per tier + file path; period, limits, threshold); write `TenantRegistryStartupTest` (`@SpringBootTest`, real seed: 15 tenants, 8/5/2, lookup of `acme`, both log lines via `OutputCaptureExtension`); verify `./gradlew :bootstrap:test` passes, including the unchanged `HealthEndpointTest`, `ServerPortTest` and `ArchitectureTest`
- [x] 5.3 Add `TenantLoadFailureAnalyzer` registered in `bootstrap/src/main/resources/META-INF/spring.factories`; write `StartupFailureTest` (`SpringApplication.run` with a missing tenants file, an unknown tier and `QUOTA_PERIOD_SECONDS=0` each throws before a web server is started; the analyzer's description names the file and problem); verify `./gradlew :bootstrap:test` passes
- [x] 5.4 Update `gateway/README.md` (current state; module layout, layer table, dependency-rules table and "Where to add beans" table gain `:adapters:persistence` / `PersistenceConfiguration`, and the `:adapters:infra` row says "communication with other services (Redis)"; a "Ports" subsection states the DDD / hexagonal rule from design D-9 — repository interfaces in the domain, implemented in `:adapters:persistence`; other outbound ports in `:app`, implemented in `:adapters:infra`; Configuration table with `TENANTS_FILE` and the five `QUOTA_*` variables, fail-fast behaviour, Docker run commands with the read-only mount) and `gateway/.env.example`; verify every variable in `application.yaml` appears in both and the documented `./gradlew` commands still run as written

## 6. Container and stack wiring

- [x] 6.1 Add `ENV TENANTS_FILE=/config/tenants.json` to `gateway/Dockerfile` (design D-6); verify by reading the Dockerfile that no tenants file is copied into the image
- [x] 6.2 In `docker-compose.yml`, mount `./config/tenants.json:/config/tenants.json:ro` into the gateway and pass the five `QUOTA_*` variables with `${VAR:-default}` defaults matching `application.yaml`; document them in the root `.env.example`; verify `docker compose config` (static, starts nothing) shows the mount and the default values, and with `QUOTA_PERIOD_SECONDS=30` in the environment shows `30`

## 7. Final check

- [x] 7.1 Run `./gradlew clean build` in `/gateway` and verify all modules compile and every test passes; run `openspec validate tenant-registry-and-config --strict` and verify it passes; verify `git status` shows no changes outside `gateway/`, `config/`, `docker-compose.yml`, `.env.example` and this change's folder

## 8. Integer tenant ids and Jackson data binding

- [x] 8.1 Switch `Tenant.id` to a positive `long` and `TenantRegistry` to `find(long id)` with a `ConcurrentHashMap<Long, Tenant>` and numeric id tie-break; update `TenantTest`, `TenantRegistryTest` and `LoadTenantRegistryTest`; verify `./gradlew :domain:test :app:test` passes
- [x] 8.2 Rewrite `JsonFileTenantRepositoryTest` for integer ids (whole number, positive, no coercion of `"1"`/`1.5`/`true`, non-string name), then reimplement `JsonFileTenantRepository` with Jackson data binding into a private `TenantEntry` record and a strict `JsonMapper` (design D-1, D-4); verify `./gradlew :adapters:persistence:test` passes
- [x] 8.3 Renumber `config/tenants.json` to ids 1..15 (design D-4), update `TenantRegistryStartupTest`, `StartupFailureTest` and the README tenants file format; verify `./gradlew clean build` passes and `openspec validate tenant-registry-and-config --strict` passes

## Checks left to the user

These need a running container or stack and are not run during implementation:

- `docker build` in `/gateway`, then `docker run` with `-v "$PWD/../config/tenants.json:/config/tenants.json:ro"` → health `UP` and container `healthy`; without the mount → non-zero exit naming `/config/tenants.json`.
- `scripts/start.sh` → gateway log shows 15 tenants from `/config/tenants.json` and the default quota settings; with `QUOTA_PERIOD_SECONDS=30` in the root `.env` → period 30 s.
