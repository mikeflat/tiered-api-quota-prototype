# Quota gateway

Spring Boot 4 service on Java 21, built with Gradle (Kotlin DSL). It will enforce a per-tenant,
fixed-window request quota by tier (Bronze/Silver/Gold) and host the admin API used by the
dashboard. Requirements, architecture and backlog are in [`../docs`](../docs).

**Current state: skeleton.** The gateway starts, answers `GET /actuator/health`, and has no
business or admin endpoint yet. Every other path returns `404`. Quota logic, the stub endpoint,
the admin API and Redis usage recording are added by later backlog items.

## Requirements

- JDK 21. Gradle picks it up automatically: `gradle/gradle-daemon-jvm.properties` asks for
  Java 21, even when `JAVA_HOME` points to another version.
- No local Gradle install. Always use the committed wrapper (`./gradlew`, or `gradlew.bat` in
  `cmd`/PowerShell).
- Docker only for the image (see [Docker image](#docker-image)). `./gradlew build` does not need
  Docker or Redis yet.

## Module layout

```
gateway/
  domain/            com.mikeflat.gateway.domain          pure business rules
  app/               com.mikeflat.gateway.app             use cases and the ports they need
  adapters/api/      com.mikeflat.gateway.adapters.api    inbound adapters (web, filter)
  adapters/infra/    com.mikeflat.gateway.adapters.infra  outbound adapters (redis, config)
  bootstrap/         com.mikeflat.gateway.bootstrap       main class, Spring wiring, executable jar
  gradle/libs.versions.toml                               pinned versions (Spring Boot, ArchUnit, OpenAPI Generator)
```

The modules map to the project's Clean Architecture layers like this:

| Module (Gradle path) | Package | Clean Architecture layer | Later content |
|---|---|---|---|
| `:domain` | `com.mikeflat.gateway.domain` | domain (pure Java) | `QuotaPeriod`, tier/tenant model, blocked-set rules |
| `:app` | `com.mikeflat.gateway.app` | application: use cases + ports | tier change, usage recording use cases; ports for counters, clock, registry |
| `:adapters:api` | `com.mikeflat.gateway.adapters.api` | inbound adapters (web, filter) | `QuotaFilter`, `StubController`, `AdminController` (subpackages `web`, `filter`) |
| `:adapters:infra` | `com.mikeflat.gateway.adapters.infra` | outbound adapters (redis, config) | Redis record script, `MetricsSnapshotJob` reader, `tenants.json` loader (subpackages `redis`, `config`) |
| `:bootstrap` | `com.mikeflat.gateway.bootstrap` | infrastructure / frameworks: main class, wiring | `GatewayApplication`, per-module `@Configuration` classes |

`adapters/` is only a folder that groups the two adapter projects. It has no build logic.

The inner modules (`domain`, `app`, `adapters:api`, `adapters:infra`) hold no classes yet. Each
keeps its root package directory with a `.gitkeep` file. Delete the `.gitkeep` when the directory
gets its first class. Empty modules still build (their `compileJava` is `NO-SOURCE`) and produce
empty jars for `bootstrap`. Add tests to a module together with its first code: a `test` task
with test sources but no discovered tests fails.

## Dependency rules

Dependencies point inward:

| Module           | May depend on                    | Spring          |
|------------------|----------------------------------|-----------------|
| `domain`         | nothing                          | no              |
| `app`            | `domain`                         | no              |
| `adapters:api`   | `app`, `domain`                  | yes (web MVC)   |
| `adapters:infra` | `app`, `domain`                  | yes (Data Redis)|
| `bootstrap`      | all of the above                 | yes             |

- `adapters:api` and `adapters:infra` never depend on each other.
- Nothing depends on `bootstrap`.
- Inter-module dependencies use `implementation`, never `api`, so nothing leaks transitively.

Two checks enforce these rules. The Gradle declarations stop disallowed imports at compile time.
`bootstrap/src/test/.../ArchitectureTest.java` (ArchUnit) fails the build if a wrong dependency
is added later.

**Temporary setting:** while the inner modules are empty, most ArchUnit rules select no classes,
which ArchUnit fails by default. `bootstrap/src/test/resources/archunit.properties` sets
`archRule.failOnEmptyShould=false` so the rules pass now and take effect as soon as code lands.
Remove that setting once every module has production classes, so a rule that silently matches
nothing (for example after a package typo) fails again.

## Where to add beans

Classes in `domain`, `app` and the adapters carry no Spring stereotype annotations. Component
scanning covers only `com.mikeflat.gateway.bootstrap`. Register each bean with a `@Bean` method
in the module's configuration class.

One exception: controllers and controller advice in `adapters:api` carry `@RestController` /
`@RestControllerAdvice`, because Spring MVC only maps handler beans with a type-level
`@Controller`. They are still registered as `@Bean`s in `ApiConfiguration`, not scanned.

| Module           | Configuration class                                   |
|------------------|-------------------------------------------------------|
| `domain`         | `bootstrap/.../config/DomainConfiguration.java`       |
| `app`            | `bootstrap/.../config/AppConfiguration.java`          |
| `adapters:api`   | `bootstrap/.../config/ApiConfiguration.java`          |
| `adapters:infra` | `bootstrap/.../config/InfraConfiguration.java`        |

`GatewayApplication` imports all four. They are empty for now.

## OpenAPI spec

The HTTP API is defined spec-first in `adapters/api/src/main/resources/openapi/gateway-api.yaml`.
At build time, `openApiGenerate` (run before `compileJava`) generates interfaces into
`com.mikeflat.gateway.adapters.api.generated.api` and DTOs into
`com.mikeflat.gateway.adapters.api.generated.model`, under `adapters/api/build/generated/openapi`.
Generated code is not committed; controllers implement the generated interfaces.

The spec is a stub with no paths for now, so nothing is generated. The stub business endpoint and
the admin API add their paths to this file.

## Configuration

Settings come from environment variables. `.env.example` lists them with their defaults.

| Variable                 | Default     | Meaning                                         |
|--------------------------|-------------|-------------------------------------------------|
| `SERVER_PORT`            | `8080`      | HTTP port the gateway listens on                |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host for usage counters (not used yet)    |
| `SPRING_DATA_REDIS_PORT` | `6379`      | Redis port (not used yet)                       |

## Health

The only actuator endpoint over HTTP is `GET /actuator/health`. It needs no credentials and
returns `200` with `{"status":"UP"}`, without component details. Other actuator paths return
`404`.

Health reports the gateway process only. Redis is not part of health yet, and the gateway does
not connect to Redis at startup (Lettuce connects on first use), so it starts and stays `UP`
with no Redis running. A Redis health component comes with `redis-failure-and-health`; liveness
and readiness will still not depend on Redis.

## Build, run and test

Run these from `gateway/`:

```bash
./gradlew build                      # compile, run all tests, build the jar
./gradlew test                       # all tests
./gradlew :bootstrap:test            # health, port and architecture tests
./gradlew :adapters:api:compileJava  # generate from the OpenAPI spec, compile
./gradlew :bootstrap:bootRun         # run from source on port 8080 (Redis not needed)
java -jar bootstrap/build/libs/gateway.jar                    # run the built jar
SERVER_PORT=9090 java -jar bootstrap/build/libs/gateway.jar   # run on another port
curl -i http://localhost:8080/actuator/health
```

`./gradlew build` produces one executable artifact: `bootstrap/build/libs/gateway.jar`.

The tests need neither Docker nor Redis yet. Testcontainers is already a test dependency; the
first Redis change adds container tests, and from then on `./gradlew test` needs a running
Docker daemon.

## Docker image

`Dockerfile` builds the gateway in two stages, so the host needs only Docker:

1. **Build** on `eclipse-temurin:21-jdk`: the committed Gradle wrapper runs
   `./gradlew :bootstrap:bootJar`. Build scripts are copied before sources, and a BuildKit
   cache mount keeps Gradle downloads, so rebuilds after source changes are fast.
2. **Runtime** on `eclipse-temurin:21-jre`: only `gateway.jar`, run as a non-root user with
   `java -jar /app/gateway.jar` on `SERVER_PORT` (default `8080`). The image has a healthcheck
   on `GET /actuator/health` that follows `SERVER_PORT`.

Tests are **not** run in the image build. Run `./gradlew build` for them.

Run these from `gateway/`:

```bash
docker build -t quota-gateway:local .
```

Run it in the background and check health, the container health status and the user:

```bash
docker run -d --name quota-gateway -p 8080:8080 quota-gateway:local
curl -i http://localhost:8080/actuator/health
docker inspect --format '{{.State.Health.Status}}' quota-gateway   # "healthy" after a few seconds
docker exec quota-gateway id -u                                    # not 0
docker rm -f quota-gateway
```

Run it on another port:

```bash
docker run --rm -e SERVER_PORT=9090 -p 9090:9090 quota-gateway:local
curl -i http://localhost:9090/actuator/health
```
