# Proposal

## Why

Backlog item 1, `bootstrap-skeleton` (docs/04 backlog.md), asks for an empty gateway, dashboard and generator that each start and answer a trivial request. This change is the **gateway slice** of that item. `/gateway` currently holds a copy of another project (the "clientsim" backend) whose Gradle multi-module layout we want to reuse, but its code, package names, Redis logic, API and docs belong to that project. Every later gateway change (backlog items 2–10) needs a clean, buildable, containerised Spring Boot skeleton to build on.

## What Changes

- Keep the template's Clean Architecture module layout in `/gateway`: `domain`, `app`, `adapters:api`, `adapters:infra`, `bootstrap`, with the same inward-only dependency rules and the ArchUnit test that enforces them. Inner modules hold no classes yet; their package directories are kept with `.gitkeep` files (no marker classes).
- Rename everything to this project: Gradle root project `gateway`, group `com.mikeflat`, packages `com.mikeflat.gateway.<module>`, main class `GatewayApplication`, executable jar `gateway.jar`, Spring application name `gateway`.
- Remove all clientsim-specific code and resources: the `/api/calls` endpoint and its OpenAPI spec (replaced by a stub spec; the generator setup stays), the client/call-count domain, the record-call use case, the Redis counter store and its Lua scripts, and every test that covers them.
- Keep every template dependency (Spring Data Redis, OpenAPI Generator, validation, spring-tx, Testcontainers, Mockito, ArchUnit) and make it inert with stub configuration: Redis connection settings are present but nothing connects at startup and Redis is not part of health yet; the OpenAPI generator runs against a stub spec with no paths. Redis is used from `async-usage-recording` on; Redis health comes with `redis-failure-and-health`.
- Keep the operational surface: `GET /actuator/health` answers `200` with `{"status":"UP"}`, no other actuator endpoint is exposed, and the HTTP port comes from `SERVER_PORT` (default `8080`).
- Keep the two-stage `Dockerfile` (JDK build with the Gradle wrapper, JRE non-root runtime with a healthcheck on `/actuator/health`), adapted to the new jar and module names, so the gateway can be built and started with plain `docker build` / `docker run`.
- Rewrite `gateway/README.md`, `.env.example` and the build-script comments for this project; delete the stray `gateway/.gitkeep`.

## Capabilities

### New Capabilities
- `gateway-runtime`: how the gateway service starts and is operated — process health endpoint, exposed actuator surface, configurable HTTP port, and running as a container image. Later changes extend it (Redis health component in `redis-failure-and-health`).

### Modified Capabilities
<!-- None: openspec/specs/ is empty. -->

## Impact

- **Code**: everything under `/gateway` (build scripts, version catalog, Java sources and tests, resources, Dockerfile, README, env example). No other folder changes.
- **Requirements covered**: the gateway part of backlog item 1 ("each service starts and answers a trivial request"); the process-status part of FR-17 (Redis as a health component is deferred to backlog item 6); NFR-4 in its skeleton form (health does not depend on Redis); NFR-9 groundwork (JUnit 5 and Testcontainers dependencies kept; the first Testcontainers test comes with the first Redis change). Consistent with D10 (health separate from business data).
- **Dependencies**: unchanged from the template (Spring Boot 4 webmvc/actuator/validation/data-redis, OpenAPI Generator, Testcontainers, JUnit 5, AssertJ, Mockito, ArchUnit, Java 21 toolchain, committed Gradle wrapper); only names and packages change.
- **Build/test**: `./gradlew build` does not need Docker for now (no Testcontainers tests remain until the first Redis change).

## Out of scope

- Root `docker-compose.yml`, the Redis service, and the `/scripts` start/stop scripts (FR-27, FR-28) — left to another `bootstrap-skeleton` slice.
- Dashboard and generator skeletons.
- Any quota, tenant, admin or stub business endpoint (`/api/**`, `/admin/**`) — backlog items 2–10.
- Liveness/readiness probe groups and Redis health (backlog item 6).
- Everything listed in docs/02 requirements.md section 6 (no auth, no database, single gateway instance, etc.).
