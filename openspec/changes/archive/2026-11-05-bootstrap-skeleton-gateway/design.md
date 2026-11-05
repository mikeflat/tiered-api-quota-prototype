# Design

## Context

`/gateway` is an untracked copy of the "clientsim" backend: a Gradle (Kotlin DSL) multi-module Spring Boot 4.1 / Java 21 project with packages `dev.clientsim.backend.*`, a spec-first `/api/calls` endpoint (OpenAPI Generator), a Redis call counter with Lua scripts, Testcontainers tests, an ArchUnit test, a two-stage Dockerfile and a README describing all of it. The module layout and build conventions are what we keep; the business code is what we drop (see proposal.md — What Changes). Requirements for the result are in `specs/gateway-runtime/spec.md`.

## Goals / Non-Goals

**Goals:**
- A minimal, buildable skeleton that keeps the template's module boundaries and wiring conventions, so later changes only add classes, not structure.
- `./gradlew build` passes on a host with JDK 21 resolution via the wrapper and no Docker.
- `docker build` + `docker run` in `/gateway` starts the gateway and reports healthy.

**Non-Goals:**
- No placeholder business code (no stub controller, no tenant model, no ports) — those arrive with backlog items 2–4 together with their tests.
- No root compose file or scripts (handled by another `bootstrap-skeleton` slice).

## Decisions

### D-1. Keep the template's five modules and map them to the project's Clean Architecture layers

| Module (Gradle path) | Package | Clean Architecture layer | Later content (from docs/03 architecture.md §2.1) |
|---|---|---|---|
| `:domain` | `com.mikeflat.gateway.domain` | domain (pure Java) | `QuotaPeriod`, tier/tenant model, blocked-set rules |
| `:app` | `com.mikeflat.gateway.app` | application: use cases + ports | tier change, usage recording use cases; ports for counters, clock, registry |
| `:adapters:api` | `com.mikeflat.gateway.adapters.api` | inbound adapters (web, filter) | `QuotaFilter`, `StubController`, `AdminController` (subpackages `web`, `filter`) |
| `:adapters:infra` | `com.mikeflat.gateway.adapters.infra` | outbound adapters (redis, config) | Redis record script, `MetricsSnapshotJob` reader, `tenants.json` loader (subpackages `redis`, `config`) |
| `:bootstrap` | `com.mikeflat.gateway.bootstrap` | infrastructure / frameworks: main class, wiring | `GatewayApplication`, per-module `@Configuration` classes |

The project context names the layers "domain / application / adapter (in, out) / infrastructure"; the template names them `domain`, `app`, `adapters:api`, `adapters:infra`, `bootstrap`. We keep the template names (user requirement: use its module structure) and document the mapping above in `gateway/README.md`.

Package root `com.mikeflat.gateway` (user choice), Gradle group `com.mikeflat`, root project name `gateway`.

*Alternative:* collapse into one module with packages per layer — rejected: weaker enforcement (compile-time module boundaries are lost) and against the instruction to reuse the template layout.

### D-2. No marker classes: empty modules keep their packages with `.gitkeep`; ArchUnit tolerates empty rules

The template's marker classes (`DomainModule`, `AppModule`, `ApiModule`, `InfraModule`) are deleted. Each inner module keeps its root package directory with a `.gitkeep` file:
`domain/src/main/java/com/mikeflat/gateway/domain/`, `app/src/main/java/com/mikeflat/gateway/app/`, `adapters/api/src/main/java/com/mikeflat/gateway/adapters/api/`, `adapters/infra/src/main/java/com/mikeflat/gateway/adapters/infra/`. Their `compileJava` is `NO-SOURCE` (the `:adapters:api` one compiles nothing generated either, see D-4) and they still produce (empty) jars for `:bootstrap`.

ArchUnit fails a rule whose `that()` selects no classes by default, and with empty inner modules four of the five rules in `ArchitectureTest` select nothing. `bootstrap/src/test/resources/archunit.properties` sets `archRule.failOnEmptyShould=false`, so the rules pass while modules are empty and take effect as soon as code lands. A comment in that file says to remove the setting once every module has production classes. A `.gitkeep` is deleted when its directory gets its first class.

*Alternative:* keep marker classes — rejected by the user; they are noise to delete later.

### D-3. Wiring stays explicit, scanning stays in `bootstrap`

`GatewayApplication` uses `@SpringBootApplication(scanBasePackageClasses = GatewayApplication.class)` and imports `DomainConfiguration`, `AppConfiguration`, `ApiConfiguration`, `InfraConfiguration` — all four empty for now, with their Javadoc kept. Inner modules carry no Spring annotations (except future controllers/advice, as the template documents). This is the convention every later change follows.

### D-4. Keep every template dependency; stub configuration makes them inert

The version catalog and module dependencies stay as in the template (only names, group and packages change), because Redis and spec-first OpenAPI are used by the following changes:

- `:adapters:api` — `org.openapi.generator` plugin, `webmvc`, `validation`, `spring-tx`; tests `spring-test`, `mockito-core`, `assertj-core`. Generator settings stay; `inputSpec` points to a stub `src/main/resources/openapi/gateway-api.yaml` (OpenAPI 3.0.3, `info` with title "Quota gateway API", `paths: {}`), `apiPackage = com.mikeflat.gateway.adapters.api.generated.api`, `modelPackage = com.mikeflat.gateway.adapters.api.generated.model`. With no paths and no schemas, `openApiGenerate` produces no classes. `stub-business-endpoint` / admin changes add paths to this file.
- `:adapters:infra` — `spring-boot-starter-data-redis`; tests `testcontainers-junit-jupiter`, `assertj-core`.
- `:bootstrap` — `webmvc`, `actuator`, `data-redis`; tests `starter-test`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `archunit-junit5`.

With `data-redis` on the classpath Boot auto-configures a Lettuce connection factory and a Redis health contributor. The factory does not connect until first use, so startup does not need Redis; the health contributor is switched off with `management.health.redis.enabled: false` (as in the template), so health stays `UP` without Redis (spec: "Health does not depend on Redis"). `redis-failure-and-health` turns it back on as a component (FR-17) while keeping probes Redis-free (NFR-4).

*Alternative:* drop the unused dependencies until needed — rejected: Redis and OpenAPI are part of the agreed stack, and keeping them lets later changes add code without touching the build definition.

### D-5. Keep the template's operational configuration

`application.yaml`: `spring.application.name: gateway`, `server.port: ${SERVER_PORT:8080}`, actuator web exposure `health` only, `show-details: never`, probe groups disabled (liveness/readiness come with item 6), `spring.mvc.problemdetails.enabled: true` (errors later become `application/problem+json`), `spring.data.redis.host: ${SPRING_DATA_REDIS_HOST:localhost}` and `port: ${SPRING_DATA_REDIS_PORT:6379}` (stub settings, not used yet), `management.health.redis.enabled: false` (D-4). `.env.example` lists `SERVER_PORT`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` with these defaults and says Redis is not used yet.

### D-6. Keep the two-stage Dockerfile with renamed artifacts

- Build stage `eclipse-temurin:21-jdk`: copy wrapper + build scripts (one `COPY` per module's `build.gradle.kts`) first, warm Gradle with `./gradlew help` under a BuildKit cache mount, then copy sources and run `./gradlew :bootstrap:bootJar`; CRLF stripping of `gradlew` stays (Windows checkouts).
- Runtime stage `eclipse-temurin:21-jre`: `curl` for the healthcheck, non-root `app` user, `/app/gateway.jar`, `EXPOSE 8080`, `HEALTHCHECK` on `/actuator/health`, `ENTRYPOINT ["java","-jar","/app/gateway.jar"]`.
- Local image tag in docs: `quota-gateway:local`. `bootJar` archive name `gateway.jar`; plain `jar` stays disabled.

### D-7. Tests kept and adapted

All in `bootstrap/src/test/java/com/mikeflat/gateway/bootstrap/`:
- `HealthEndpointTest` — health `200` JSON `UP`; `/actuator/env` and `/actuator/beans` `404`; add `/api/v1/resource` → `404`.
- `ServerPortTest` — `SERVER_PORT` honoured.
- `ArchitectureTest` — same five rules with `ROOT = "com.mikeflat.gateway"`; `archunit.properties` beside it in `src/test/resources` (D-2).
- `HttpProbe` — `get` only (POST helpers removed with their users).

Deleted: `CallsEndpointTest`, `RedisOutageTest`, `BackendRestartTest`, `RedisTestContainer`, `CallsControllerTest`, `CallCountsMapperTest`, `RedisCallCounterStoreIT`, `RecordCallUseCaseTest`, `ClientIdTest`. A context-load-without-Redis check is implicit in `HealthEndpointTest`: the test JVM has `data-redis` on the classpath but no Redis server is started, and health must still be `UP`. The Testcontainers dependencies stay unused until the first Redis change adds container tests. The Docker scenarios of the spec are verified manually (commands in the README) because Testcontainers cannot run inside the image build and a Docker-in-test for the image is not worth it for a skeleton.

### D-8. Files kept, rewritten or removed at the `/gateway` root

- Kept as is: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `gradle/gradle-daemon-jvm.properties`, `.gitattributes`, `.dockerignore`, `.gitignore` (overlaps the root `.gitignore` but keeps `/gateway` self-contained for `docker build`-adjacent tooling; harmless).
- Rewritten: `settings.gradle.kts`, `build.gradle.kts`, module `build.gradle.kts` files, `gradle/libs.versions.toml`, `Dockerfile`, `.env.example`, `README.md`.
- Added: `.gitkeep` in the four inner module packages (D-2), `adapters/api/src/main/resources/openapi/gateway-api.yaml` (stub, D-4), `bootstrap/src/test/resources/archunit.properties` (D-2).
- Removed: root `gateway/.gitkeep`, all `dev/clientsim/**` sources and tests including the four marker classes, `adapters/api/src/main/resources/openapi/calls-api.yaml`, `adapters/infra/src/main/resources/redis/`.

## Risks / Trade-offs

- [Gradle 9 fails a `test` task that has test sources but discovers no tests] → inner modules keep no test sources at all (task becomes `NO-SOURCE`); tests are added together with code.
- [Empty modules look like dead weight] → `.gitkeep` files and the README explain the layout; removing structure now would force a restructure in items 2–4.
- [`archRule.failOnEmptyShould=false` also hides a rule that silently matches nothing because of a wrong package name] → the setting is temporary (comment in the file); remove it once every module has classes.
- [OpenAPI Generator rejects or warns on a spec with empty `paths`] → if it fails, the stub defines a single placeholder operation `GET /api/v1/resource` (the endpoint of backlog item 3) without implementing a controller; the spec's 404 scenario still holds because nothing implements the generated interface.
- [A Redis connection is attempted at startup and fails or delays it] → Lettuce connects lazily by default; `HealthEndpointTest` runs without Redis and would catch it.
- [Spring Boot 4 / Gradle 9.8 versions pinned by the template] → kept as is; they are current and already proven by the template build.
- [Docker behaviour is checked manually, not by an automated test] → README gives the exact `docker build` / `docker run` / `curl` / `docker inspect` commands; the compose slice will exercise it again.
- [Windows host: CRLF `gradlew` breaks the Linux build stage] → `.gitattributes` forces LF and the Dockerfile still strips `\r`.

## Migration Plan

Not applicable: `/gateway` is untracked and nothing depends on it yet. Rollback = delete the folder contents.
