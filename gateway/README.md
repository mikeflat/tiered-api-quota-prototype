# Quota gateway

Spring Boot 4 service on Java 21, built with Gradle (Kotlin DSL). It will enforce a per-tenant,
fixed-window request quota by tier (Bronze/Silver/Gold) and host the admin API used by the
dashboard. Requirements, architecture and backlog are in [`../docs`](../docs).

**Current state: stub business endpoint behind the tenant guard, usage counted in Redis, quota
enforced, admin API.** At
startup the gateway loads the tenants from the shared `../config/tenants.json` into an in-memory
registry and reads the quota settings (period, limit per tier, near threshold); it refuses to
start if either is invalid. It answers `GET /actuator/health`, the stub business endpoint
`GET /api/v1/resource` (see [Business API](#business-api)), which requires a known tenant in
`X-Tenant-Id`, and the admin endpoints `GET /admin/tiers`, `PUT /admin/tenants/{id}/tier` and
`GET /admin/snapshot` (see [Admin API](#admin-api)). Every other path returns `404` (under
`/api/` only after the tenant guard has passed). Every admitted business request is counted per
tenant and period in Redis, asynchronously and off the request path (see
[Usage recording](#usage-recording)). Once a tenant's recorded usage reaches its tier's limit,
its requests get `429 Too Many Requests` with `Retry-After` until the period ends or its tier is
changed (see [Quota enforcement](#quota-enforcement)).

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
| `:domain` | `com.mikeflat.gateway.domain` | domain (pure Java) | now: `tenant` (`Tier`, `Tenant`, `TenantRegistry` with `search(TenantFilter)` and `changeTier`, `TenantFilter` (name contains, id, tier), `TenantRepository` port, `TenantLoadException`), `quota` (`QuotaSettings` with `periodIndexAt`, `periodAt`, `secondsUntilNextPeriod`, `nearBoundary` and `limitsOf`, `QuotaPeriod` (index, end, seconds left), `UsageLimits` (a tier's limit and near-limit boundary, with `reachedBy(used)`), `TenantStatus` (`ok`/`near`/`over` of a `used` against `UsageLimits`), `BlockedTenants` (the per-period blocked set, with `blockIf` and `unblock`), `AdmissionPolicy` with its `Admission` outcomes `Admitted`, `Blocked` (with `retryAfterSeconds`) and `TenantUnknown`, `UsageEvent` (`allowedAt`, `rejectedAt`), `UsageOutcome`, `TrafficMetrics` with `SecondTraffic` and `PeriodTotals`) |
| `:app` | `com.mikeflat.gateway.app` | application: use cases + ports | now: `tenant.LoadTenantRegistry`, `quota.AdmitBusinessCall` (submits an allowed event for each admitted call and a rejected event for each blocked call), `quota.BlockTenantsOverLimit` (run by the writer after each recorded event; blocks a tenant when `limits.reachedBy(used)` and the limit current at blocking time is reached too), `usage.TenantUsageLimits` (`of(tenantId)`: the `UsageLimits` of the tenant's tier at that moment, from `TenantRegistry` and `QuotaSettings`), `usage.UsageRecorder` (hands events to the writer executor; on the writer resolves the tenant's limits, skips an event of an unknown tenant, applies it through the port and passes the returned `used` with the same limits to its `RecordedUsageListener`; reports outage start and end through `RecordingOutage`), `usage.RecordedUsageListener` (`usageRecorded(event, used, limits)`), `usage.UsageCounter` port (`record(event, limits)` → `used`) with `UsageRecordingFailedException`, `usage.UsageRecordingAlerts` port, `admin.ListTiers` (→ `TierLimit`s), `admin.ChangeTenantTier` (→ `TierChange.Changed` or `TenantNotFound`), `metrics.TrafficMetricsReader` port with `MetricsReadFailedException`, `metrics.LatestTrafficMetrics` (in-memory cache, 3 s max age), `metrics.RefreshTrafficMetrics`, `snapshot.BuildAdminSnapshot` (→ `AdminSnapshot` with `TenantRows.Available` / `Unavailable`, `TenantRow`, `RowUsage`), `snapshot.TenantUsageReader` port (`usageOf`, `flaggedTenants`) with `TenantUsage` and `UsageReadFailedException`, `snapshot.PageRequest`, `snapshot.SnapshotQuery` |
| `:adapters:api` | `com.mikeflat.gateway.adapters.api` | inbound adapters (web, filter) | now: `filter.QuotaFilter` (tenant guard and `429` for blocked tenants) with its `TenantIdHeader` parser, `web.StubController`, `web.AdminController` with `AdminParameters` (tier/status/id/paging parsing, `400`) and `AdminDtoMapper`, `web.ConstraintViolationProblemAdvice` (bean-validation failures of admin parameters → `400` problem) |
| `:adapters:infra` | `com.mikeflat.gateway.adapters.infra` | outbound adapters: communication with other services (Redis) | now: `redis.RedisKeys` (builders for every key), `redis.RedisUsageCounter` (implements `UsageCounter`) and the `redis/record-usage.lua` script resource (tenant counters, global traffic counts, near/over sets), `redis.RedisTrafficMetricsReader` (implements `TrafficMetricsReader`, one pipeline), `redis.RedisTenantUsageReader` (implements `TenantUsageReader`), `logging.Slf4jUsageRecordingAlerts` (implements `UsageRecordingAlerts`) |
| `:adapters:persistence` | `com.mikeflat.gateway.adapters.persistence` | outbound adapters: data sources, one subpackage per storage technology | now: `file.JsonFileTenantRepository`; later: a `db` package if tenants move to a database |
| `:bootstrap` | `com.mikeflat.gateway.bootstrap` | infrastructure / frameworks: main class, wiring | `GatewayApplication`, per-module `@Configuration` classes, `@ConfigurationProperties`, `TenantLoadFailureAnalyzer`, `config.UsageWriterExecutor` (the single-thread usage writer with its bounded queue), `config.MetricsRefreshScheduler` (the `metrics-refresh` thread) |

`adapters/` is only a folder that groups the adapter projects. It has no build logic.

Add tests to a module together with its first code: a `test` task with test sources but no
discovered tests fails.

### Ports

The gateway follows a DDD / hexagonal rule for ports:

| Port kind | Declared in | Implemented in | Examples |
|---|---|---|---|
| Repository of domain objects (loading/storing tenants, …) | the domain package of the aggregate it serves, with its exceptions | `:adapters:persistence` (one subpackage per storage technology) | `TenantRepository` → `JsonFileTenantRepository` |
| Other outbound port (Redis usage recording and metrics, other services) | `:app`, next to the use case that needs it | `:adapters:infra` | `UsageCounter` → `RedisUsageCounter`, `UsageRecordingAlerts` → `Slf4jUsageRecordingAlerts`, `TrafficMetricsReader` → `RedisTrafficMetricsReader`, `TenantUsageReader` → `RedisTenantUsageReader` |
| Inbound port | `:app` use cases | called by `:adapters:api` (and by bootstrap at startup or on a timer) | `LoadTenantRegistry`, `AdmitBusinessCall`, `ListTiers`, `ChangeTenantTier`, `BuildAdminSnapshot`, `RefreshTrafficMetrics` |

So moving tenants to a database only adds a `db` package to `:adapters:persistence` and changes
`PersistenceConfiguration`; the domain, use cases and other adapters stay untouched.

Time comes from `java.time.Clock`, a JDK type injected into `AdmitBusinessCall`,
`BlockTenantsOverLimit`, `RefreshTrafficMetrics` and `BuildAdminSnapshot`, so no custom clock
port is needed and tests use `Clock.fixed`. Likewise `UsageRecorder` takes a plain
`java.util.concurrent.Executor`; the bounded thread pool behind it is built in `:bootstrap`, and
so is the one-second timer that calls `RefreshTrafficMetrics.refresh()`.

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
| `domain`               | `bootstrap/.../config/DomainConfiguration.java`       | `QuotaSettings` (from `QuotaProperties`), `BlockedTenants`, `AdmissionPolicy` (from `TenantRegistry`, `BlockedTenants`, `QuotaSettings`) |
| `app`                  | `bootstrap/.../config/AppConfiguration.java`          | `LoadTenantRegistry`, `TenantRegistry`, `Clock` (UTC system clock), `BlockTenantsOverLimit` (with `TenantUsageLimits` for the re-check), `TenantUsageLimits`, `UsageRecorder` (with `TenantUsageLimits`, the writer executor, `BlockTenantsOverLimit` as its listener and `UsageRecordingAlerts`), `AdmitBusinessCall`, `ListTiers`, `ChangeTenantTier`, `LatestTrafficMetrics`, `RefreshTrafficMetrics`, `BuildAdminSnapshot` |
| `adapters:api`         | `bootstrap/.../config/ApiConfiguration.java`          | `StubController`, `AdminController`, `ConstraintViolationProblemAdvice`, `FilterRegistrationBean<QuotaFilter>` (URL pattern `/api/*`, `REQUEST` dispatches only) |
| `adapters:infra`       | `bootstrap/.../config/InfraConfiguration.java`        | `RedisScript<Long>` (`redis/record-usage.lua`), `UsageCounter` (`RedisUsageCounter`, key TTL two periods), `UsageRecordingAlerts` (`Slf4jUsageRecordingAlerts`), `usageWriterExecutor` (from `UsageRecordingProperties`, destroyed with `shutdownNow`), `TrafficMetricsReader` (`RedisTrafficMetricsReader`), `TenantUsageReader` (`RedisTenantUsageReader`), `MetricsRefreshScheduler` (calls `RefreshTrafficMetrics` every second, closed with the context) |
| `adapters:persistence` | `bootstrap/.../config/PersistenceConfiguration.java`  | `TenantRepository` (`JsonFileTenantRepository`, from `TenantsProperties`) |

`GatewayApplication` imports all five.

## OpenAPI spec

The HTTP API is defined spec-first in `adapters/api/src/main/resources/openapi/gateway-api.yaml`.
At build time, `openApiGenerate` (run before `compileJava`) generates interfaces into
`com.mikeflat.gateway.adapters.api.generated.api` and DTOs into
`com.mikeflat.gateway.adapters.api.generated.model`, under `adapters/api/build/generated/openapi`.
Generated code is not committed; controllers implement the generated interfaces.

The spec defines `GET /api/v1/resource` (tag `Resource`) with its `200`, `400` and `429`
(with `Retry-After`) responses, which generates `ResourceApi` and the `MockResource` DTO. The
tag `Admin` holds `GET /admin/tiers`, `PUT /admin/tenants/{id}/tier` and `GET /admin/snapshot`,
which generate `AdminApi` and the DTOs `TierLimit`, `TierChangeRequest`, `TenantTier`,
`AdminSnapshot`, `QuotaPeriod`, `TrafficMetrics`, `SecondTraffic`, `PeriodTotals`, `TenantPage`
and `TenantRow`. `ProblemDetail` is mapped to Spring's own class, so no model is generated for
it.

The generated interfaces carry `@Validated`, so Spring validates the `id`, `page` and `size`
bounds through a method-validation proxy, which throws `ConstraintViolationException`.
`ConstraintViolationProblemAdvice` turns that into a `400` problem body for `AdminController`.

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
the admin API, and look-alikes such as `/apix/...`) are not guarded.

**Quota exhausted.** A known tenant that is blocked for the current period gets `429 Too Many
Requests` from `QuotaFilter`, before any controller runs. `Retry-After` holds the whole seconds
until the next period boundary (`(p + 1) × QUOTA_PERIOD_SECONDS − epochSecond`, `1` to
`QUOTA_PERIOD_SECONDS`). The body is the same problem shape, with no used, limit or remaining
values, and no rate-limit headers are sent:

```json
{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Quota for the current period is exhausted","instance":"/api/v1/resource"}
```

## Admin API

Three endpoints for the admin dashboard, outside `/api/`, so they need no `X-Tenant-Id` and are
never counted or limited. There is no authentication (out of scope). Errors are
`application/problem+json` (`type`, `title`, `status`, `detail`, `instance`); a `detail` never
echoes the rejected value.

**`GET /admin/tiers`** → `200`, the tiers in order Bronze, Silver, Gold with their configured
limit per period. No Redis call.

```json
[{"name":"Bronze","limit":100},{"name":"Silver","limit":1000},{"name":"Gold","limit":10000}]
```

**`PUT /admin/tenants/{id}/tier`** with body `{"tier":"Gold"}` (any letter case) → `200` with the
tenant after the change and the limit of its new tier:

```json
{"id":1,"name":"Acme Corp","tier":"Gold","limit":10000}
```

The change lives only in gateway memory: the tenants file is not written and a restart restores
the seed tiers. It makes no Redis call, so it works during an outage, and it unblocks the tenant
(see [Quota enforcement](#quota-enforcement)). Setting the same tier again also unblocks; the
tenant's next event blocks it again if it is still over. Errors:

| Case | Status |
|---|---|
| `id` is an integer but no tenant has it | `404` |
| `id` not a positive integer (`acme`, `0`) | `400` |
| body missing or not JSON, `tier` missing or not Bronze/Silver/Gold | `400` |

**`GET /admin/snapshot`** → `200` with `period`, `metrics` and `tenants` (shortened: `perSecond`
always has 60 entries, oldest first):

```json
{
  "period": {"index": 100000, "endsAt": "1970-03-11T10:41:00Z", "secondsLeft": 15},
  "metrics": {
    "perSecond": [{"second": 5999985, "allowed": 0, "rejected": 0}, {"second": 6000044, "allowed": 3, "rejected": 1}],
    "totals": {"allowed": 120, "rejected": 7},
    "tenantsNear": 2,
    "tenantsOver": 1
  },
  "tenants": {
    "rowsAvailable": true,
    "rows": [{"id": 1, "name": "Acme Corp", "tier": "Bronze", "limit": 100, "used": 85, "rejected": 0, "status": "near"}],
    "total": 15,
    "page": 0,
    "size": 10
  }
}
```

| Query parameter | Meaning | Invalid → `400` |
|---|---|---|
| `name` | name contains this text, ignoring case | — |
| `id` | exact tenant id | not a positive integer |
| `tier` | `Bronze`, `Silver`, `Gold`, any letter case | unknown tier |
| `status` | `ok`, `near`, `over`, any letter case | unknown status |
| `page` | page from `0`, default `0` | negative or not an integer |
| `size` | rows per page, default `10` | outside `1..100` or not an integer |

Filters combine; an empty value (`?name=&tier=`) means no filter, and unknown parameters are
ignored. Rows are sorted by name (case-insensitive, then id) and cut at offset `page × size`;
`total` counts all matching tenants, and a page past the end has no rows.

- **`period`** comes from the gateway clock at request time, never from Redis.
- **`metrics`** is the global part: `perSecond` covers the 60 complete seconds before the last
  read (`0` for quiet seconds), `totals` the current period, `tenantsNear` / `tenantsOver` the
  sizes of the period's near and over sets. It is read by the daemon thread `metrics-refresh`
  once per second (`RefreshTrafficMetrics`, reading period `p` and second `now − 1`) in one
  pipelined round trip (two `MGET` of 60 keys, one `MGET` of the two totals, two `SCARD`), and
  kept in memory (`LatestTrafficMetrics`). Snapshot requests only read that cache, so the global
  part costs the same whatever the number of tenants or open dashboards.
- **Rows** carry `id`, `name`, `tier` and `limit` from the registry, and `used`, `rejected`
  (`0` when absent) and `status` from one `MGET` of `quota:used:*` and `quota:rejected:*` for the
  tenants on the page only. `status` is computed from `used` against the tenant's *current*
  limit: `over` at `used ≥ limit`, `near` from the near boundary, else `ok`.
- **Status filter.** `status=near` / `status=over` keeps tenants that are members of the current
  period's set (`SMEMBERS`); `status=ok` keeps tenants in neither set (`SUNION` of both). The sets
  change only on a tenant's next event, so right after a tier change a tenant can still be listed
  under `status=over` while its row shows `ok`.
- **Redis cost per snapshot request:** none for the global part; at most one round trip for a
  status filter plus one `MGET` for the page (none for an empty page).

**`null` when Redis can't be read.** The snapshot still answers `200`:

| Part | Redis unreachable or hanging |
|---|---|
| `period` | always present |
| `metrics` | `null` when the last background read failed or none succeeded within the last 3 s; back within about a second after Redis returns |
| rows without `status` filter | present with `id`, `name`, `tier`, `limit`; `used`, `rejected`, `status` are `null`; `total`, `page`, `size` correct |
| rows with a `status` filter | `"rowsAvailable": false`, `"rows": null`, `"total": null` |

`rowsAvailable` is `true` in every other response. A hung Redis delays the row read by at most
the `1s` command timeout; snapshot read failures are not logged.

## Quota enforcement

The quota decision on the request thread is in memory only: one registry lookup and one lookup
in `BlockedTenants`, with no Redis call.

- **Blocked set per period.** `BlockedTenants` holds the tenants known to be over their limit,
  tied to one period index. A lookup only matches the current period index, so at the boundary
  every tenant is served again without a reset job; the old set is replaced on the first block
  of the new period, and a block for an older period is ignored.
- **The writer blocks.** After each recorded allowed event, `BlockTenantsOverLimit` compares the
  `used` returned by the record script with the limit of the tenant's tier at that moment (the
  same `UsageLimits` the script got for the near/over sets). At
  `used ≥ limit`, and only if the event's period is still the current one, the tenant is added
  to the blocked set. Rejected events and late events of a past period never block.
- **Tier change unblocks.** `PUT /admin/tenants/{id}/tier` updates the registry first and then
  removes the tenant from the blocked set, in memory and without Redis. Its next request is
  served; its next applied allowed event is checked against the new limit and the existing
  counters, which the change leaves untouched. Other blocked tenants stay blocked.
- **Blocking re-checks the current limit.** An event whose `used` was compared with the old,
  lower limit could otherwise block a tenant right after its upgrade. `BlockedTenants.blockIf`
  and `unblock` are `synchronized`, and `BlockTenantsOverLimit` re-reads the tenant's current
  limit inside that lock, so either the block happens first and the tier change undoes it, or the
  re-check already sees the new tier. After a downgrade, the event checked against the old,
  higher limit does not block; the tenant's next allowed event does. `isBlocked` on the request
  path stays lock-free.
- **Rejections are counted, not used.** A `429` produces a rejected usage event, so
  `quota:rejected:{tenantId}:{p}` grows and `quota:used:{tenantId}:{p}` does not.
- **Bounded overshoot.** Requests arriving between the one that reaches the limit and the writer
  applying its event are still served, so `used` can end above the limit. The overshoot is the
  tenant's events still waiting in the writer queue; once the writer applies the event reaching
  the limit, every later request in that period gets `429`.
- **Empty after a restart.** The blocked set lives only in gateway memory. After a restart a
  tenant over its limit is served until the writer records its next allowed event, which blocks
  it again (accepted limitation).
- **Redis down.** Tenants already blocked keep getting `429` while Redis is unreachable, until
  the period ends, and every other tenant is served. Nothing is recorded then, so no tenant is
  newly blocked, and at a period boundary during the outage every tenant is served again. This
  holds whether Redis goes down at startup or mid-run. Once Redis is back, recording resumes
  and tenants over their limit are blocked again without a restart. If Redis *hung* rather than
  refused connections, record calls that timed out on the gateway may still be applied by Redis
  when it answers again, so `used` can be higher after recovery than the events counted before
  the outage (accepted; it only makes enforcement stricter).

## Usage recording

Every business request that passes the tenant guard produces one usage event: tenant id, outcome
(`allowed` when served, `rejected` when answered `429`), the period index
`p = floor(epochSeconds / QUOTA_PERIOD_SECONDS)` and the epoch second, all read from the clock
when the request arrives. Requests rejected by the guard (`400`) produce no event.

- **Request thread.** `AdmitBusinessCall` hands the event to `UsageRecorder.submit`, which only
  offers it to the writer's bounded queue and returns. It never waits for Redis, for the writer
  or for queue space, and does no I/O. The response is the same whether recording succeeds,
  fails or is dropped.
- **Bounded queue, drop on overflow.** The writer is a single-thread `ThreadPoolExecutor` with an
  `ArrayBlockingQueue` of `USAGE_QUEUE_CAPACITY` waiting events (the writer holds one more in
  flight). When the queue is full the new event is dropped; drops are not counted.
- **Limits at write time.** For each event the writer looks up the tenant's tier at that moment
  (`TenantUsageLimits`, in memory) and gets its limit and near-limit boundary (`UsageLimits`).
  The same values go to the record script and to `BlockTenantsOverLimit`, so a tier change
  applies from the tenant's next recorded event. An event of a tenant missing from the registry
  is skipped without a Redis call.
- **One writer, one Redis call per event.** The daemon thread `usage-writer` applies events in
  order, each with one call of `redis/record-usage.lua` (`EVALSHA`, falling back to `EVAL` when
  Redis has lost its script cache). In that one atomic call the script increments the tenant's
  counter, the global per-second count and the global period total for the event's outcome, then
  updates the tenant's near/over set membership (see [Redis keys](#redis-keys)). It returns the
  tenant's `used` for the event's period, which `BlockTenantsOverLimit` compares with the limit
  (see [Quota enforcement](#quota-enforcement)).
- **Redis failures.** A failed or timed-out call (`SPRING_DATA_REDIS_TIMEOUT`, default `1s`)
  discards the event (it is not counted anywhere); the writer continues with the next event and
  never retries. Once Redis is back, later events are counted without a restart.
- **Lifecycle.** The writer thread starts with the first event, so startup does not contact
  Redis. On shutdown the executor is stopped with `shutdownNow`: queued events are discarded and
  the writer is interrupted, so shutdown never waits for Redis.

**Outage log lines.** The writer reports the start and the end of a Redis outage once each,
through the `UsageRecordingAlerts` port (`Slf4jUsageRecordingAlerts` in `:adapters:infra`). The
first failed call after a success (or the first call after startup) logs one line, and further
failures log nothing:

```
WARN  Redis unavailable, usage recording failing: no new tenants are blocked and counting is paused (<Redis error message>)
```

The first successful call after failures logs:

```
INFO  Redis available again, usage recording resumed
```

With no business traffic, nothing is logged while Redis is down (health still reports it).
Drops (queue full) log nothing.

**Lost events are not tracked.** Events of an outage are simply not counted, and enforcement
relies only on the current blocked set. The gateway does not report or log how many events were
lost, failed or dropped, and keeps no count of them, not even in memory.

### Redis keys

Periods are aligned to the UTC epoch; a new period starts from zero because its keys are new,
with no reset job. Every key gets its TTL when the script creates it, and later writes do not
extend it. `{p}` is the event's period index and `{s}` its epoch second.

| Key | Written by | Value | TTL |
|---|---|---|---|
| `quota:used:{tenantId}:{p}` | allowed events (`INCR`) | requests counted toward the tenant's quota in period `p` | two periods |
| `quota:rejected:{tenantId}:{p}` | rejected events (`INCR`) | requests rejected for the tenant in period `p` | two periods |
| `traffic:allowed:{s}` / `traffic:rejected:{s}` | every event, by outcome (`INCR`) | requests of all tenants in epoch second `s` | `120` s, independent of the period |
| `traffic:total:allowed:{p}` / `traffic:total:rejected:{p}` | every event, by outcome (`INCR`) | requests of all tenants in period `p` | two periods |
| `quota:near:{p}` | every event (`SADD` / `SREM`) | ids of tenants near their limit in period `p` | two periods (`EXPIRE … NX`) |
| `quota:over:{p}` | every event (`SADD` / `SREM`) | ids of tenants at or over their limit in period `p` | two periods (`EXPIRE … NX`) |

"Two periods" is `2 × QUOTA_PERIOD_SECONDS`.

**Status rule.** After counting an event (allowed or rejected), the script compares the tenant's
`used` in `p` with the limit and near-limit boundary current at write time: `used ≥ limit` puts
the tenant only in `quota:over:{p}`, `nearBoundary ≤ used < limit` only in `quota:near:{p}`, and
anything lower in neither. The boundary is the smallest count reaching
`QUOTA_NEAR_THRESHOLD_PERCENT` of the limit (`80` of `100`, `8000` of `10000`). Membership
changes only when an event of that tenant is applied, so after a tier change it stays as it was
until the tenant's next request. A set gets its TTL with `EXPIRE … NX`, which also covers a set
that Redis deleted when it emptied and that is recreated later; this needs Redis 7 or newer
(compose and the tests use `redis:8.10-alpine`).

An event always uses its own period index and epoch second, so an event applied after a boundary
still counts toward the second and period it arrived in. While Redis is unreachable nothing is
written, including the traffic counts and sets, and nothing is repaired later.

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

The only actuator endpoint over HTTP is health, with its probe and component paths. None needs
credentials. Other actuator paths (`/actuator/env`, `/actuator/beans`, …) return `404`.

| Path | Depends on Redis | Redis reachable | Redis unreachable or hanging |
|---|---|---|---|
| `GET /actuator/health` | yes (`redis` component) | `200` `{"status":"UP","components":{"redis":{"status":"UP"},…}}` | `503` `{"status":"DOWN","components":{"redis":{"status":"DOWN"},…}}` |
| `GET /actuator/health/liveness` | no | `200` `{"status":"UP"}` | `200` `{"status":"UP"}` |
| `GET /actuator/health/readiness` | no | `200` `{"status":"UP"}` | `200` `{"status":"UP"}` |

- **Status only.** Responses list component statuses (`show-components: always`) but no details
  (`show-details: never`): no Redis host, port, version or error message.
- **Bounded Redis check.** The Redis component sends a `PING` through the shared Lettuce
  connection, so it is limited by `SPRING_DATA_REDIS_TIMEOUT` / `SPRING_DATA_REDIS_CONNECT_TIMEOUT`
  (`1s`). A hung Redis never holds the health call for long.
- **Recovery without restart.** Health goes `DOWN` when Redis stops and `UP` again once it
  answers.
- **Probes ignore Redis.** The liveness and readiness groups are pinned to `livenessState` and
  `readinessState` (`management.endpoint.health.group.*` in `application.yaml`), so no Redis
  check can join them. The Docker `HEALTHCHECK` uses liveness, so a Redis outage never marks the
  gateway container unhealthy.
- **Startup without Redis.** The gateway does not connect to Redis at startup (Lettuce connects
  on first use). It starts with no Redis running: liveness and readiness are `UP`,
  `/actuator/health` is `503` `DOWN`, and business requests still succeed but are not counted.

`/actuator/health` returns `503` during a Redis outage. Clients must read its body, which says
enforcement is degraded; it does not mean the gateway is down.

## Build, run and test

Run these from `gateway/`:

```bash
./gradlew build                      # compile, run all tests, build the jar
./gradlew test                       # all tests
./gradlew :domain:test               # domain unit tests (tiers, tenants, registry incl. filtering and tier change, tenant filter, quota settings and period, usage limits, tenant status, usage events, blocked set incl. unblock and blockIf, admission policy)
./gradlew :app:test                  # use case tests (tenant loading, request admission, blocking over the limit incl. tier change races, overshoot, tenant usage limits, usage recorder, recording outage alerts, tier listing and change, metrics cache and refresh, page request, admin snapshot)
./gradlew :adapters:api:test         # tenant guard and 429 filter, problem body, stub controller, admin controller (MockMvcTester), admin parameters, DTO mapping, constraint-violation advice
./gradlew :adapters:infra:test       # Redis usage counter and record script incl. traffic counts, TTLs and near/over sets, traffic metrics reader, tenant usage reader (Testcontainers Redis), outage log lines
./gradlew :adapters:persistence:test # tenants file adapter tests
./gradlew :bootstrap:test            # configuration, startup, health with and without Redis, probes, business endpoint, usage writer, metrics refresh scheduler, usage recording with and without Redis, traffic metrics recording, quota enforcement (incl. Redis down), admin API, tier change, admin without Redis, Redis stopped mid-run (incl. snapshot metrics), port and architecture tests
./gradlew :adapters:api:compileJava  # generate from the OpenAPI spec, compile
./gradlew :bootstrap:bootRun         # run from source on port 8080 (Redis not needed)
java -jar bootstrap/build/libs/gateway.jar                    # run the built jar (reads ../config/tenants.json)
SERVER_PORT=9090 java -jar bootstrap/build/libs/gateway.jar   # run on another port
QUOTA_PERIOD_SECONDS=30 java -jar bootstrap/build/libs/gateway.jar   # override a quota setting
curl -i http://localhost:8080/actuator/health            # 200 UP with Redis, 503 DOWN without
curl -i http://localhost:8080/actuator/health/liveness   # 200 UP with or without Redis
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
   on `GET /actuator/health/liveness` that follows `SERVER_PORT`, so the container stays
   `healthy` while Redis is down.

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
curl -i http://localhost:8080/actuator/health            # 200 UP with Redis, 503 DOWN without
curl -i http://localhost:8080/actuator/health/liveness   # 200 UP with or without Redis
docker inspect --format '{{.State.Health.Status}}' quota-gateway   # "healthy" after a few seconds
docker exec quota-gateway id -u                                    # not 0
docker logs quota-gateway | grep -E "Loaded|Quota settings"        # tenants and quota settings
docker rm -f quota-gateway
```

Run it on another port with a shorter period:

```bash
docker run --rm -e SERVER_PORT=9090 -e QUOTA_PERIOD_SECONDS=30 -p 9090:9090 \
  -v "$PWD/../config/tenants.json:/config/tenants.json:ro" quota-gateway:local
curl -i http://localhost:9090/actuator/health/liveness
```
