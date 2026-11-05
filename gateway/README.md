# Quota gateway

Spring Boot 4 service on Java 21, built with Gradle (Kotlin DSL). It will enforce a per-tenant,
fixed-window request quota by tier (Bronze/Silver/Gold) and host the admin API used by the
dashboard. Requirements, architecture and backlog are in [`../docs`](../docs).

**Current state: tenant registry and quota settings.** At startup the gateway loads the tenants
from the shared `../config/tenants.json` into an in-memory registry and reads the quota settings
(period, limit per tier, near threshold); it refuses to start if either is invalid. It answers
`GET /actuator/health` and has no business or admin endpoint yet. Every other path returns `404`.
The `X-Tenant-Id` guard, quota enforcement, the stub endpoint, the admin API and Redis usage
recording are added by later backlog items.

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
  domain/                com.mikeflat.gateway.domain                pure business rules and repository ports
  app/                   com.mikeflat.gateway.app                   use cases and their (non-repository) ports
  adapters/api/          com.mikeflat.gateway.adapters.api          inbound adapters (web, filter)
  adapters/infra/        com.mikeflat.gateway.adapters.infra        communication with other services (Redis)
  adapters/persistence/  com.mikeflat.gateway.adapters.persistence  repository implementations (file, later db)
  bootstrap/             com.mikeflat.gateway.bootstrap             main class, Spring wiring, executable jar
  gradle/libs.versions.toml                                         pinned versions (Spring Boot, ArchUnit, OpenAPI Generator)
```

The modules map to the project's Clean Architecture layers like this:

| Module (Gradle path) | Package | Clean Architecture layer | Content (now / later) |
|---|---|---|---|
| `:domain` | `com.mikeflat.gateway.domain` | domain (pure Java) | now: `tenant` (`Tier`, `Tenant`, `TenantRegistry`, `TenantRepository` port, `TenantLoadException`), `quota` (`QuotaSettings`); later: `QuotaPeriod`, blocked-set rules |
| `:app` | `com.mikeflat.gateway.app` | application: use cases + ports | now: `tenant.LoadTenantRegistry`; later: tier change, usage recording use cases; ports for counters, metrics, clock |
| `:adapters:api` | `com.mikeflat.gateway.adapters.api` | inbound adapters (web, filter) | later: `QuotaFilter`, `StubController`, `AdminController` (subpackages `web`, `filter`) |
| `:adapters:infra` | `com.mikeflat.gateway.adapters.infra` | outbound adapters: communication with other services (Redis) | later: Redis record script, `MetricsSnapshotJob` reader (subpackage `redis`) |
| `:adapters:persistence` | `com.mikeflat.gateway.adapters.persistence` | outbound adapters: data sources, one subpackage per storage technology | now: `file.JsonFileTenantRepository`; later: a `db` package if tenants move to a database |
| `:bootstrap` | `com.mikeflat.gateway.bootstrap` | infrastructure / frameworks: main class, wiring | `GatewayApplication`, per-module `@Configuration` classes, `@ConfigurationProperties`, `TenantLoadFailureAnalyzer` |

`adapters/` is only a folder that groups the adapter projects. It has no build logic.

`adapters:api` and `adapters:infra` hold no classes yet. Each keeps its root package directory
with a `.gitkeep` file. Delete the `.gitkeep` when the directory gets its first class. Empty
modules still build (their `compileJava` is `NO-SOURCE`) and produce empty jars for `bootstrap`.
Add tests to a module together with its first code: a `test` task with test sources but no
discovered tests fails.

### Ports

The gateway follows a DDD / hexagonal rule for ports:

| Port kind | Declared in | Implemented in | Examples |
|---|---|---|---|
| Repository of domain objects (loading/storing tenants, …) | the domain package of the aggregate it serves, with its exceptions | `:adapters:persistence` (one subpackage per storage technology) | `TenantRepository` → `JsonFileTenantRepository` |
| Other outbound port (Redis usage recording and metrics, clock, other services) | `:app`, next to the use case that needs it | `:adapters:infra` | coming with usage recording |
| Inbound port | `:app` use cases | called by `:adapters:api` (and by bootstrap at startup) | `LoadTenantRegistry` |

So moving tenants to a database only adds a `db` package to `:adapters:persistence` and changes
`PersistenceConfiguration`; the domain, use cases and other adapters stay untouched.

## Dependency rules

Dependencies point inward:

| Module                 | May depend on                    | Spring          |
|------------------------|----------------------------------|-----------------|
| `domain`               | nothing                          | no              |
| `app`                  | `domain`                         | no              |
| `adapters:api`         | `app`, `domain`                  | yes (web MVC)   |
| `adapters:infra`       | `app`, `domain`                  | yes (Data Redis)|
| `adapters:persistence` | `domain` (plus Jackson)          | no              |
| `bootstrap`            | all of the above                 | yes             |

- The adapter modules never depend on each other.
- `adapters:persistence` does not depend on `app`: it implements domain ports only.
- Nothing depends on `bootstrap`.
- Inter-module dependencies use `implementation`, never `api`, so nothing leaks transitively.

Two checks enforce these rules. The Gradle declarations stop disallowed imports at compile time.
`bootstrap/src/test/.../ArchitectureTest.java` (ArchUnit) fails the build if a wrong dependency
is added later.

**Temporary setting:** while `adapters:api` and `adapters:infra` are empty, the rules about them
select no classes, which ArchUnit fails by default. `bootstrap/src/test/resources/archunit.properties`
sets `archRule.failOnEmptyShould=false` so the rules pass now and take effect as soon as code
lands. Remove that setting once every module has production classes, so a rule that silently
matches nothing (for example after a package typo) fails again.

## Where to add beans

Classes in `domain`, `app` and the adapters carry no Spring stereotype annotations. Component
scanning covers only `com.mikeflat.gateway.bootstrap`. Register each bean with a `@Bean` method
in the module's configuration class.

One exception: controllers and controller advice in `adapters:api` carry `@RestController` /
`@RestControllerAdvice`, because Spring MVC only maps handler beans with a type-level
`@Controller`. They are still registered as `@Bean`s in `ApiConfiguration`, not scanned.

| Module                 | Configuration class                                   | Beans now |
|------------------------|-------------------------------------------------------|-----------|
| `domain`               | `bootstrap/.../config/DomainConfiguration.java`       | `QuotaSettings` (from `QuotaProperties`) |
| `app`                  | `bootstrap/.../config/AppConfiguration.java`          | `LoadTenantRegistry`, `TenantRegistry` |
| `adapters:api`         | `bootstrap/.../config/ApiConfiguration.java`          | — |
| `adapters:infra`       | `bootstrap/.../config/InfraConfiguration.java`        | — |
| `adapters:persistence` | `bootstrap/.../config/PersistenceConfiguration.java`  | `TenantRepository` (`JsonFileTenantRepository`, from `TenantsProperties`) |

`GatewayApplication` imports all five.

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

| Variable                       | Default                  | Meaning                                                        |
|--------------------------------|--------------------------|----------------------------------------------------------------|
| `SERVER_PORT`                  | `8080`                   | HTTP port the gateway listens on                               |
| `SPRING_DATA_REDIS_HOST`       | `localhost`              | Redis host for usage counters (not used yet)                   |
| `SPRING_DATA_REDIS_PORT`       | `6379`                   | Redis port (not used yet)                                      |
| `TENANTS_FILE`                 | `../config/tenants.json` | Tenants file, relative to the working directory; read once at startup |
| `QUOTA_PERIOD_SECONDS`         | `60`                     | Fixed-window period length in whole seconds (production: `86400`), ≥ 1 |
| `QUOTA_LIMIT_BRONZE`           | `100`                    | Requests per period for Bronze tenants, ≥ 1                    |
| `QUOTA_LIMIT_SILVER`           | `1000`                   | Requests per period for Silver tenants, ≥ 1                    |
| `QUOTA_LIMIT_GOLD`             | `10000`                  | Requests per period for Gold tenants, ≥ 1                      |
| `QUOTA_NEAR_THRESHOLD_PERCENT` | `80`                     | A tenant is "near limit" from this percent of its limit, 1..99 |

The `TENANTS_FILE` default fits running the jar from `gateway/`. `./gradlew :bootstrap:bootRun`
and `./gradlew test` set it to the repository's `config/tenants.json` (absolute path), and the
Docker image sets it to `/config/tenants.json`. The quota defaults live in
`bootstrap/src/main/resources/application.yaml` and are repeated in the root `docker-compose.yml`.

**Tenants file format:** a JSON array of objects with an integer `id` (a JSON number, unique,
1 or more, up to a Java `long`; sent later in `X-Tenant-Id`), a non-blank string `name` and a
`tier` (`Bronze`, `Silver` or `Gold`, any letter case). Other fields are ignored. The file is
read with Jackson data binding, with no type coercion: `"1"`, `1.5` or `true` as an id, or a
number as a name, are rejected. Example entry: `{ "id": 1, "name": "Acme Corp", "tier": "Bronze" }`.

**Fail fast:** the tenants and quota settings are loaded before the web server starts. If the
tenants file is missing or invalid (not a JSON array of objects, empty, a missing or blank field,
an id that is not a positive whole number, an unknown tier, a duplicate id) or a quota setting is out of range or not a number, the gateway
prints an `APPLICATION FAILED TO START` report naming the file or setting and the problem, and
exits with a non-zero code without ever answering on its port. On success it logs two lines:

```
Loaded 15 tenants (Bronze 8, Silver 5, Gold 2) from /config/tenants.json
Quota settings: period 60 s, limits Bronze 100, Silver 1000, Gold 10000, near threshold 80 %
```

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
./gradlew :domain:test               # domain unit tests (tiers, tenants, registry, quota settings)
./gradlew :app:test                  # use case tests
./gradlew :adapters:persistence:test # tenants file adapter tests
./gradlew :bootstrap:test            # configuration, startup, health, port and architecture tests
./gradlew :adapters:api:compileJava  # generate from the OpenAPI spec, compile
./gradlew :bootstrap:bootRun         # run from source on port 8080 (Redis not needed)
java -jar bootstrap/build/libs/gateway.jar                    # run the built jar (reads ../config/tenants.json)
SERVER_PORT=9090 java -jar bootstrap/build/libs/gateway.jar   # run on another port
QUOTA_PERIOD_SECONDS=30 java -jar bootstrap/build/libs/gateway.jar   # override a quota setting
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

The image contains **no tenants file**. It reads `TENANTS_FILE=/config/tenants.json`, so mount
the repository's `config/tenants.json` there (read-only). Without the mount the container exits
with a non-zero code and its log names `/config/tenants.json`.

Tests are **not** run in the image build. Run `./gradlew build` for them.

Run these from `gateway/`:

```bash
docker build -t quota-gateway:local .
```

Run it in the background and check health, the container health status and the user:

```bash
docker run -d --name quota-gateway -p 8080:8080 \
  -v "$PWD/../config/tenants.json:/config/tenants.json:ro" quota-gateway:local
curl -i http://localhost:8080/actuator/health
docker inspect --format '{{.State.Health.Status}}' quota-gateway   # "healthy" after a few seconds
docker exec quota-gateway id -u                                    # not 0
docker logs quota-gateway | grep -E "Loaded|Quota settings"        # tenants and quota settings
docker rm -f quota-gateway
```

Run it on another port with a shorter period:

```bash
docker run --rm -e SERVER_PORT=9090 -e QUOTA_PERIOD_SECONDS=30 -p 9090:9090 \
  -v "$PWD/../config/tenants.json:/config/tenants.json:ro" quota-gateway:local
curl -i http://localhost:9090/actuator/health
```
