# Quota gateway

Spring Boot 4 service on Java 21, built with Gradle (Kotlin DSL). It will enforce a per-tenant,
fixed-window request quota by tier (Bronze/Silver/Gold) and host the admin API used by the
dashboard. Requirements, architecture and backlog are in [`../docs`](../docs).

**Current state: stub business endpoint behind the tenant guard, usage counted in Redis.** At
startup the gateway loads the tenants from the shared `../config/tenants.json` into an in-memory
registry and reads the quota settings (period, limit per tier, near threshold); it refuses to
start if either is invalid. It answers `GET /actuator/health` and the stub business endpoint
`GET /api/v1/resource` (see [Business API](#business-api)), which requires a known tenant in
`X-Tenant-Id`. Every other path returns `404` (under `/api/` only after the tenant guard has
passed). Every admitted business request is counted per tenant and period in Redis,
asynchronously and off the request path (see [Usage recording](#usage-recording)). Nothing is
enforced yet: every known tenant still gets `200`. Quota enforcement and the admin API are added
by later backlog items.

## Requirements

- JDK 21. Gradle picks it up automatically: `gradle/gradle-daemon-jvm.properties` asks for
  Java 21, even when `JAVA_HOME` points to another version.
- No local Gradle install. Always use the committed wrapper (`./gradlew`, or `gradlew.bat` in
  `cmd`/PowerShell).
- Docker for the image (see [Docker image](#docker-image)) and for the tests: `./gradlew build`
  and `./gradlew test` start Redis containers with Testcontainers, so a Docker daemon must be
  running. No Redis install is needed.

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
| `:domain` | `com.mikeflat.gateway.domain` | domain (pure Java) | now: `tenant` (`Tier`, `Tenant`, `TenantRegistry`, `TenantRepository` port, `TenantLoadException`), `quota` (`QuotaSettings` with `periodIndexAt`, `AdmissionPolicy` with its `Admission` outcomes, `UsageEvent`, `UsageOutcome`); later: seconds to the period boundary, blocked-set rules in `AdmissionPolicy` |
| `:app` | `com.mikeflat.gateway.app` | application: use cases + ports | now: `tenant.LoadTenantRegistry`, `quota.AdmitBusinessCall` (submits an allowed usage event for each admitted call), `usage.UsageRecorder` (hands events to the writer executor, applies them through the port, counts recorded, dropped and failed events), `usage.UsageCounter` port with `UsageRecordingFailedException`; later: tier change use case, blocked-set decision in `UsageRecorder`, metrics ports |
| `:adapters:api` | `com.mikeflat.gateway.adapters.api` | inbound adapters (web, filter) | now: `filter.QuotaFilter` (tenant guard) with its `TenantIdHeader` parser, `web.StubController`; later: quota checks in `QuotaFilter`, `AdminController` |
| `:adapters:infra` | `com.mikeflat.gateway.adapters.infra` | outbound adapters: communication with other services (Redis) | now: `redis.RedisUsageCounter` (implements `UsageCounter`) and the `redis/record-usage.lua` script resource; later: global metrics in the script, `MetricsSnapshotJob` reader |
| `:adapters:persistence` | `com.mikeflat.gateway.adapters.persistence` | outbound adapters: data sources, one subpackage per storage technology | now: `file.JsonFileTenantRepository`; later: a `db` package if tenants move to a database |
| `:bootstrap` | `com.mikeflat.gateway.bootstrap` | infrastructure / frameworks: main class, wiring | `GatewayApplication`, per-module `@Configuration` classes, `@ConfigurationProperties`, `TenantLoadFailureAnalyzer`, `config.UsageWriterExecutor` (the single-thread usage writer with its bounded queue) |

`adapters/` is only a folder that groups the adapter projects. It has no build logic.

Add tests to a module together with its first code: a `test` task with test sources but no
discovered tests fails.

### Ports

The gateway follows a DDD / hexagonal rule for ports:

| Port kind | Declared in | Implemented in | Examples |
|---|---|---|---|
| Repository of domain objects (loading/storing tenants, …) | the domain package of the aggregate it serves, with its exceptions | `:adapters:persistence` (one subpackage per storage technology) | `TenantRepository` → `JsonFileTenantRepository` |
| Other outbound port (Redis usage recording and metrics, other services) | `:app`, next to the use case that needs it | `:adapters:infra` | `UsageCounter` → `RedisUsageCounter` |
| Inbound port | `:app` use cases | called by `:adapters:api` (and by bootstrap at startup) | `LoadTenantRegistry`, `AdmitBusinessCall` |

So moving tenants to a database only adds a `db` package to `:adapters:persistence` and changes
`PersistenceConfiguration`; the domain, use cases and other adapters stay untouched.

Time comes from `java.time.Clock`, a JDK type injected into `AdmitBusinessCall`, so no custom
clock port is needed and tests use `Clock.fixed`. Likewise `UsageRecorder` takes a plain
`java.util.concurrent.Executor`; the bounded thread pool behind it is built in `:bootstrap`.

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

ArchUnit runs with its default `failOnEmptyShould=true`: a rule whose classes match nothing (for
example after a package typo) fails instead of passing silently.

## Where to add beans

Classes in `domain`, `app` and the adapters carry no Spring stereotype annotations. Component
scanning covers only `com.mikeflat.gateway.bootstrap`. Register each bean with a `@Bean` method
in the module's configuration class.

One exception: controllers and controller advice in `adapters:api` carry `@RestController` /
`@RestControllerAdvice`, because Spring MVC only maps handler beans with a type-level
`@Controller`. They are still registered as `@Bean`s in `ApiConfiguration`, not scanned.

| Module                 | Configuration class                                   | Beans now |
|------------------------|-------------------------------------------------------|-----------|
| `domain`               | `bootstrap/.../config/DomainConfiguration.java`       | `QuotaSettings` (from `QuotaProperties`), `AdmissionPolicy` (from `TenantRegistry`) |
| `app`                  | `bootstrap/.../config/AppConfiguration.java`          | `LoadTenantRegistry`, `TenantRegistry`, `Clock` (UTC system clock), `UsageRecorder` (with the writer executor), `AdmitBusinessCall` |
| `adapters:api`         | `bootstrap/.../config/ApiConfiguration.java`          | `StubController`, `FilterRegistrationBean<QuotaFilter>` (URL pattern `/api/*`, `REQUEST` dispatches only) |
| `adapters:infra`       | `bootstrap/.../config/InfraConfiguration.java`        | `RedisScript<Long>` (`redis/record-usage.lua`), `UsageCounter` (`RedisUsageCounter`, key TTL two periods), `usageWriterExecutor` (from `UsageRecordingProperties`, destroyed with `shutdownNow`) |
| `adapters:persistence` | `bootstrap/.../config/PersistenceConfiguration.java`  | `TenantRepository` (`JsonFileTenantRepository`, from `TenantsProperties`) |

`GatewayApplication` imports all five.

## OpenAPI spec

The HTTP API is defined spec-first in `adapters/api/src/main/resources/openapi/gateway-api.yaml`.
At build time, `openApiGenerate` (run before `compileJava`) generates interfaces into
`com.mikeflat.gateway.adapters.api.generated.api` and DTOs into
`com.mikeflat.gateway.adapters.api.generated.model`, under `adapters/api/build/generated/openapi`.
Generated code is not committed; controllers implement the generated interfaces.

The spec defines `GET /api/v1/resource` (tag `Resource`), which generates `ResourceApi` and the
`MockResource` DTO. `ProblemDetail` is mapped to Spring's own class, so no model is generated for
it. The admin API adds its paths to this file later.

## Business API

`GET /api/v1/resource` is the stubbed upstream. For a known tenant it returns `200` with
`Content-Type: application/json` and always the same body:

```json
{"id":"mock-resource","message":"Served by the quota gateway stub"}
```

The body is identical for every tenant and tier, and the response carries no usage, limit or
remaining-quota headers. `StubController` is the single place to replace with a real upstream.

**Tenant guard.** Every request to a path under `/api/` (any method, including paths that do
not exist) must carry `X-Tenant-Id` with the decimal id of a tenant in the in-memory registry.
`QuotaFilter`, a servlet filter registered for `/api/*`, checks this before any controller runs,
using only gateway memory (no Redis, no file read). The header is trusted, not authenticated.
The value must be 1 to 18 ASCII digits naming a registered tenant (leading zeros are accepted);
otherwise the gateway answers `400` with `Content-Type: application/problem+json`. The check
is split by layer: `TenantIdHeader` (adapter) reads the header text, `AdmitBusinessCall` (use
case) is what the filter calls, and `AdmissionPolicy` (domain) decides whether the tenant is
admitted:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Missing X-Tenant-Id header","instance":"/api/v1/resource"}
```

The `detail` is `Missing X-Tenant-Id header` when the header is absent or blank, and
`X-Tenant-Id does not identify a known tenant` for any other value (malformed, `0`, negative,
too long or not registered). The header value is never echoed in the body or logs. With a known
tenant, an unknown path under `/api/` returns `404`. Paths outside `/api/` (`/actuator/health`,
later the admin API, and look-alikes such as `/apix/...`) are not guarded.

## Usage recording

Every business request that passes the tenant guard produces one usage event: tenant id, outcome
(`allowed`; `rejected` starts with quota enforcement), the period index
`p = floor(epochSeconds / QUOTA_PERIOD_SECONDS)` and the epoch second, all read from the clock
when the request arrives. Requests rejected by the guard (`400`) produce no event.

- **Request thread.** `AdmitBusinessCall` hands the event to `UsageRecorder.submit`, which only
  offers it to the writer's bounded queue and returns. It never waits for Redis, for the writer
  or for queue space, and does no I/O. The response is the same whether recording succeeds,
  fails or is dropped.
- **Bounded queue, drop on overflow.** The writer is a single-thread `ThreadPoolExecutor` with an
  `ArrayBlockingQueue` of `USAGE_QUEUE_CAPACITY` waiting events (the writer holds one more in
  flight). When the queue is full the new event is dropped and the dropped count grows.
- **One writer, one Redis call per event.** The daemon thread `usage-writer` applies events in
  order, each with one call of `redis/record-usage.lua` (`EVALSHA`, falling back to `EVAL` when
  Redis has lost its script cache). The script returns the tenant's `used` for the event's
  period (not used yet; quota enforcement compares it with the limit).
- **Redis failures.** A failed or timed-out call (`SPRING_DATA_REDIS_TIMEOUT`, default `1s`)
  discards the event and grows the failed count; the writer continues with the next event and
  never retries. Once Redis is back, later events are counted without a restart.
- **Lifecycle.** The writer thread starts with the first event, so startup does not contact
  Redis. On shutdown the executor is stopped with `shutdownNow`: queued events are discarded and
  the writer is interrupted, so shutdown never waits for Redis.

The recorded, dropped and failed counts are kept in memory (`UsageRecorder.stats()`); logging
them and Redis health come with `redis-failure-and-health`.

**Redis keys.** Periods are aligned to the UTC epoch; a new period starts from zero because its
keys are new, with no reset job. Each key gets a TTL of two periods (`2 × QUOTA_PERIOD_SECONDS`)
when the script creates it, and later increments do not extend it.

| Key | Written by | Value |
|---|---|---|
| `quota:used:{tenantId}:{p}` | allowed events (`INCR`) | requests counted toward the tenant's quota in period `p` |
| `quota:rejected:{tenantId}:{p}` | rejected events (`INCR`) | requests rejected for the tenant in period `p` |

An event always uses its own period index, so an event applied after the period boundary still
counts toward the period it arrived in.

## Configuration

Settings come from environment variables. `.env.example` lists them with their defaults.

| Variable                       | Default                  | Meaning                                                        |
|--------------------------------|--------------------------|----------------------------------------------------------------|
| `SERVER_PORT`                  | `8080`                   | HTTP port the gateway listens on                               |
| `SPRING_DATA_REDIS_HOST`       | `localhost`              | Redis host for usage counters                                  |
| `SPRING_DATA_REDIS_PORT`       | `6379`                   | Redis port                                                     |
| `SPRING_DATA_REDIS_TIMEOUT`    | `1s`                     | Redis command timeout; bounds how long one event can hold the writer |
| `SPRING_DATA_REDIS_CONNECT_TIMEOUT` | `1s`                | Redis connect timeout                                          |
| `TENANTS_FILE`                 | `../config/tenants.json` | Tenants file, relative to the working directory; read once at startup |
| `QUOTA_PERIOD_SECONDS`         | `60`                     | Fixed-window period length in whole seconds (production: `86400`), ≥ 1 |
| `QUOTA_LIMIT_BRONZE`           | `100`                    | Requests per period for Bronze tenants, ≥ 1                    |
| `QUOTA_LIMIT_SILVER`           | `1000`                   | Requests per period for Silver tenants, ≥ 1                    |
| `QUOTA_LIMIT_GOLD`             | `10000`                  | Requests per period for Gold tenants, ≥ 1                      |
| `QUOTA_NEAR_THRESHOLD_PERCENT` | `80`                     | A tenant is "near limit" from this percent of its limit, 1..99 |
| `USAGE_QUEUE_CAPACITY`         | `10000`                  | Usage events waiting for the writer, ≥ 1; when full, new events are dropped and counted |

The `TENANTS_FILE` default fits running the jar from `gateway/`. `./gradlew :bootstrap:bootRun`
and `./gradlew test` set it to the repository's `config/tenants.json` (absolute path), and the
Docker image sets it to `/config/tenants.json`. The quota and `USAGE_QUEUE_CAPACITY` defaults
live in `bootstrap/src/main/resources/application.yaml` and are repeated in the root
`docker-compose.yml`.

**Tenants file format:** a JSON array of objects with an integer `id` (a JSON number, unique,
1 or more, up to a Java `long`; sent by clients in `X-Tenant-Id`, which accepts at most 18
digits), a non-blank string `name` and a
`tier` (`Bronze`, `Silver` or `Gold`, any letter case). Other fields are ignored. The file is
read with Jackson data binding, with no type coercion: `"1"`, `1.5` or `true` as an id, or a
number as a name, are rejected. Example entry: `{ "id": 1, "name": "Acme Corp", "tier": "Bronze" }`.

**Fail fast:** the tenants and quota settings are loaded before the web server starts. If the
tenants file is missing or invalid (not a JSON array of objects, empty, a missing or blank field,
an id that is not a positive whole number, an unknown tier, a duplicate id) or a quota or
`USAGE_QUEUE_CAPACITY` setting is out of range or not a number, the gateway
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
with no Redis running; business requests still succeed, they are just not counted. A Redis health component comes with `redis-failure-and-health`; liveness
and readiness will still not depend on Redis.

## Build, run and test

Run these from `gateway/`:

```bash
./gradlew build                      # compile, run all tests, build the jar
./gradlew test                       # all tests
./gradlew :domain:test               # domain unit tests (tiers, tenants, registry, quota settings, usage events)
./gradlew :app:test                  # use case tests (tenant loading, request admission, usage recorder)
./gradlew :adapters:api:test         # tenant guard filter, problem body and stub controller tests
./gradlew :adapters:infra:test       # Redis usage counter and record script (Testcontainers Redis)
./gradlew :adapters:persistence:test # tenants file adapter tests
./gradlew :bootstrap:test            # configuration, startup, health, business endpoint, usage writer, usage recording with and without Redis, port and architecture tests
./gradlew :adapters:api:compileJava  # generate from the OpenAPI spec, compile
./gradlew :bootstrap:bootRun         # run from source on port 8080 (Redis not needed)
java -jar bootstrap/build/libs/gateway.jar                    # run the built jar (reads ../config/tenants.json)
SERVER_PORT=9090 java -jar bootstrap/build/libs/gateway.jar   # run on another port
QUOTA_PERIOD_SECONDS=30 java -jar bootstrap/build/libs/gateway.jar   # override a quota setting
curl -i http://localhost:8080/actuator/health
curl -i -H "X-Tenant-Id: 1" http://localhost:8080/api/v1/resource   # 200 with the mock payload
curl -i http://localhost:8080/api/v1/resource                        # 400 problem body
```

`./gradlew build` produces one executable artifact: `bootstrap/build/libs/gateway.jar`.

`./gradlew test` and `./gradlew build` need a running Docker daemon: the `:adapters:infra` and
`:bootstrap` tests start `redis:8.10-alpine` containers with Testcontainers. No Redis install is
needed. The other Spring tests do not need Redis: their recording attempts against the default
`localhost:6379` simply fail and are counted.

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
