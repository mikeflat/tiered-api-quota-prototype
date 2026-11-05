# Design

## Context

See proposal.md for motivation and specs/usage-recording/spec.md for the behaviour.

Current gateway state (observed in code):

- `QuotaFilter` (`:adapters:api`) parses `X-Tenant-Id` and calls the `AdmitBusinessCall` use case (`:app`), which delegates to the pure `AdmissionPolicy` (`:domain`) and returns `Admission.Admitted(Tenant)` or `TenantUnknown`. No I/O.
- `QuotaSettings` (`:domain`) holds the validated period, tier limits and near threshold; `QuotaProperties` (`:bootstrap`) binds `quota.*` with Bean Validation, so invalid values already stop startup.
- `:adapters:infra` is empty (only `.gitkeep`) but already depends on `spring-boot-starter-data-redis` and Testcontainers. `InfraConfiguration` is empty.
- `application.yaml` sets the Redis host/port, keeps the Redis health indicator off, and comments that Redis is "not used yet". Lettuce connects lazily, so startup works without Redis; the default Lettuce command timeout is 60 s.
- `ArchitectureTest` forbids Spring in `:domain` and `:app`, and `:adapters:api` ↔ `:adapters:infra` dependencies.
- Bootstrap HTTP tests (`BusinessEndpointTest`, etc.) run without Redis and must keep passing.

## Goals / Non-Goals

**Goals:**
- A recording path whose cost on the request thread is one `Executor.execute` (a bounded `ArrayBlockingQueue.offer` inside the JDK pool) plus a clock read.
- No hand-written queue, worker loop or thread lifecycle: the JDK `ThreadPoolExecutor` provides them. The recording rules in `:app` stay framework-free and unit-testable with a direct executor, with the thread pool and Redis kept at the edges.
- A Lua script and key model that items 5 and 7 extend without renaming keys or changing the event shape.

**Non-Goals:**
- Using the returned `used` (item 5), logging failures/drops and Redis health (item 6), global traffic keys and near/over sets (item 7).
- Draining the queue on shutdown, batching, multiple writers.

## Decisions

### D-1. Layer placement

| Element | Layer / module | Package | Kind |
|---|---|---|---|
| `UsageOutcome` (`ALLOWED`, `REJECTED`) | domain `:domain` | `domain.quota` | enum |
| `UsageEvent(long tenantId, UsageOutcome outcome, long periodIndex, long epochSecond)` | domain | `domain.quota` | record, validates ids ≥ 1, period ≥ 0 |
| `QuotaSettings.periodIndexAt(long epochSecond)` | domain | `domain.quota` | pure `Math.floorDiv(epochSecond, periodSeconds)` (the architecture's `QuotaPeriod` calculation; item 5 adds seconds-to-boundary next to it) |
| `UsageEvent.allowedAt(Tenant, Instant, QuotaSettings)` factory | domain | `domain.quota` | builds the event from the request instant |
| `UsageCounter` | application `:app` | `app.usage` | outbound port: `long record(UsageEvent event)` returns `used`; throws `UsageRecordingFailedException` |
| `UsageRecordingFailedException` | application | `app.usage` | unchecked, raised by adapters |
| `UsageRecorder` | application | `app.usage` | `submit` hands work to an injected `java.util.concurrent.Executor`; applies events and keeps counters |
| `UsageRecordingStats(long recorded, long dropped, long failed)` | application | `app.usage` | read-only snapshot of the counters |
| `AdmitBusinessCall` (changed) | application | `app.quota` | on `Admitted`, submits an allowed event |
| `RedisUsageCounter` | adapter `:adapters:infra` | `adapters.infra.redis` | implements `UsageCounter` with Spring Data Redis |
| `redis/record-usage.lua` | adapter resource | `:adapters:infra` `src/main/resources` | the record script |
| usage writer executor | infrastructure `:bootstrap` | `bootstrap.config` (`InfraConfiguration`) | single-thread `ThreadPoolExecutor` with a bounded queue, built as a bean |
| `UsageRecordingProperties` | infrastructure | `bootstrap.config` | `usage-recording.queue-capacity` |

`UsageCounter` is an application port, not a domain repository: it does not load or store domain objects, it talks to another service (README "Ports" table). The time source is `java.time.Clock`, a JDK type, injected into `AdmitBusinessCall`; no custom clock port is introduced, since `Clock.fixed` already gives tests full control. `QuotaFilter` is unchanged: it still calls `AdmitBusinessCall.admit` and switches on `Admission`.

Alternative considered: putting the executor and the recording logic in `:adapters:infra` behind a fire-and-forget `UsageRecorder` port. Rejected because item 5 needs application logic on the writer side (compare `used` with the tenant's current limit, check the event's period is still current, add to the blocked set). Keeping `apply` in `:app` lets item 5 add that logic to a use case, with only the Redis call behind a port and only the thread pool built in bootstrap.

### D-2. `UsageRecorder`: a JDK executor instead of a hand-written queue

- `UsageRecorder(UsageCounter counter, Executor executor)` with three `LongAdder`s (recorded, dropped, failed). `java.util.concurrent.Executor` is a JDK type, so `:app` stays free of Spring and `ArchitectureTest` is unchanged.
- `submit(UsageEvent event)`: `executor.execute(() -> apply(event))`; on `RejectedExecutionException` increments `dropped`. With the bounded pool of D-4 this is a non-blocking `offer` on its queue, so the request thread never waits.
- `apply(UsageEvent event)` (private, runs on the writer thread): calls `counter.record(event)`, increments `recorded`; on `UsageRecordingFailedException` increments `failed`. Item 5 adds the blocked-set decision here, using the returned `used`.
- `stats()` returns `UsageRecordingStats`.

Only `UsageRecordingFailedException` is caught and counted; the adapter is responsible for translating Redis errors into it. Any other runtime exception would be a bug: `ThreadPoolExecutor` reports it to the thread's uncaught-exception handler and replaces the worker, so recording continues without a hand-written guard.

Capacity semantics: the pool's queue holds `queueCapacity` waiting events, and the single worker holds one more in flight. "Queue full" in the spec means the waiting queue is full while the writer is busy.

Alternative considered (the previous version of this design): a hand-written `ArrayBlockingQueue` with `processNext(Duration)` and a `SmartLifecycle` owning a poll loop, stop flag, interrupt and join. Rejected as reimplementing `ThreadPoolExecutor`: same queue, same single thread, more code. The executor costs one lambda allocation per event, negligible at the prototype and production rates.

Alternative considered: `Executors.newSingleThreadExecutor()` / `newFixedThreadPool`. Rejected: they use an **unbounded** `LinkedBlockingQueue`, so during a Redis outage memory grows without limit (violates FR-6b, D7a). `CallerRunsPolicy` is also rejected: it would run the Redis call on the request thread.

### D-3. Redis adapter and Lua script

`record-usage.lua`:

```
KEYS[1] = quota:used:{tenantId}:{p}
KEYS[2] = quota:rejected:{tenantId}:{p}
ARGV[1] = "allowed" | "rejected"
ARGV[2] = ttl seconds (2 × periodSeconds)
```

1. If allowed: `n = INCR KEYS[1]`; if `n == 1` then `EXPIRE KEYS[1] ttl`; return `n`.
2. If rejected: `r = INCR KEYS[2]`; if `r == 1` then `EXPIRE KEYS[2] ttl`; return `tonumber(GET KEYS[1]) or 0`.

- Key names exactly as architecture §4. Both keys are passed as `KEYS` (not built in Lua) so the script stays cluster-friendly per tenant (architecture §8: same `{tenantId}` placement is a later hash-tag change).
- TTL only on creation (`n == 1`) keeps the original expiry; `EXPIRE … NX` would also work on Redis 7+ (compose uses `redis:8.10-alpine`) but the `== 1` form is version-independent.
- Item 7 adds global keys and near/over sets to the same script; the argument list is extended, keys above stay.

`RedisUsageCounter(StringRedisTemplate template, RedisScript<Long> script, long ttlSeconds)`:
- builds the two keys from the event, calls `template.execute(script, keys, outcome, ttl)`. Spring Data Redis executes `EVALSHA` and, on `NOSCRIPT`, falls back to `EVAL` (which also caches the script), satisfying "one call per event" in the normal case and the "script unknown" scenario.
- catches `DataAccessException` (including `RedisConnectionFailureException`, `QueryTimeoutException`) and rethrows `UsageRecordingFailedException` with the cause.
- The script is loaded once from the classpath into a `DefaultRedisScript<Long>` at wiring time (a resource read, not a Redis call), so startup does not contact Redis.

Alternative considered: `INCR` + `EXPIRE` as two commands or a `MULTI`. Rejected: two round trips and not extensible to item 7's atomic multi-key update (FR-12).

### D-4. Writer executor (bootstrap)

`InfraConfiguration` builds the writer as a bean:

```
new ThreadPoolExecutor(1, 1, 0L, MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        daemon thread factory named "usage-writer",
        new ThreadPoolExecutor.AbortPolicy())
```

- One core and max thread: exactly one writer (D5), events applied in submission order.
- `ArrayBlockingQueue` of `USAGE_QUEUE_CAPACITY`: bounded memory; `AbortPolicy` makes overflow a `RejectedExecutionException` that `UsageRecorder` counts as dropped.
- Declared with `@Bean(destroyMethod = "shutdownNow")`. This must be explicit: Spring would otherwise infer `close()`, which on Java 19+ waits for every queued event to finish and could hold shutdown for `capacity × timeout` while Redis is down. `shutdownNow` discards queued events (spec: may be lost) and interrupts the worker; the daemon thread factory ensures a Redis call stuck until its 1 s timeout cannot keep the JVM alive.
- The thread starts lazily on the first `execute`, so startup does nothing with Redis.

A platform thread rather than a virtual one: a single long-lived worker blocking on a Lettuce future gains nothing from virtual threads.

### D-5. Configuration

- `usage-recording.queue-capacity: ${USAGE_QUEUE_CAPACITY:10000}` bound to `UsageRecordingProperties(@NotNull @Min(1) Integer queueCapacity)` with `@Validated`, so `0`, negatives and non-numbers stop startup with the property name and value, matching `quota.*` behaviour (existing `StartupFailureTest` style). 10,000 is ~50 s of the prototype's 200 req/s and ~2.4 s at the production peak — enough to absorb a Redis hiccup without unbounded memory.
- `spring.data.redis.timeout: ${SPRING_DATA_REDIS_TIMEOUT:1s}` and `spring.data.redis.connect-timeout: ${SPRING_DATA_REDIS_CONNECT_TIMEOUT:1s}`. Without them a dead Redis holds the writer for Lettuce's 60 s default per event and the queue fills almost immediately. Item 6 may tune these together with the health timeout.
- `docker-compose.yml` passes `USAGE_QUEUE_CAPACITY` through with the same default (the file already mirrors `application.yaml` defaults).
- Comments in `application.yaml` stating Redis is not used yet are updated; the Redis health indicator stays disabled (item 6).

### D-6. Wiring

- `DomainConfiguration`: unchanged (`QuotaSettings` is already a bean).
- `AppConfiguration`: `Clock clock()` (`Clock.systemUTC()`), `UsageRecorder usageRecorder(UsageCounter, Executor usageWriterExecutor)`, `AdmitBusinessCall(AdmissionPolicy, UsageRecorder, QuotaSettings, Clock)`.
- `InfraConfiguration`: `RedisScript<Long> recordUsageScript()` from `classpath:redis/record-usage.lua`, `UsageCounter usageCounter(StringRedisTemplate, RedisScript<Long>, QuotaSettings)` with TTL `2 × periodSeconds`.
- `InfraConfiguration` also declares the `usageWriterExecutor` bean of D-4 from `UsageRecordingProperties` (it drives the outbound side) and enables those properties.
- `StringRedisTemplate` comes from Boot auto-configuration.

### D-7. Testing

| Layer | Test | Tooling |
|---|---|---|
| domain | `UsageEventTest` (validation, `allowedAt` period/second), `QuotaSettingsTest` additions for `periodIndexAt` (boundary, 60 s, 86400 s) | JUnit, AssertJ |
| app | `UsageRecorderTest`: with a direct executor (`Runnable::run`) — event recorded and counted, failure counted and next event still recorded, events applied in submission order; with an always-rejecting executor — event dropped and counted, `submit` returns normally; stats; `AdmitBusinessCallTest`: admitted → one allowed event with fixed-clock period and second, unknown → none | JUnit with a hand-written fake `UsageCounter`, `Clock.fixed` |
| infra adapter | `RedisUsageCounterTest` against `redis:8.10-alpine` via Testcontainers: allowed increments and return values, rejected increments `rejected` and returns `used` / `0`, tenants and periods separate, TTL set on create and not refreshed, works after `SCRIPT FLUSH`, connection failure → `UsageRecordingFailedException` (container stopped or unreachable port) | Testcontainers `GenericContainer`, Lettuce connection factory built in the test |
| bootstrap | `UsageWriterExecutorTest` on the real bean configuration with a fake counter blocked on a latch: capacity `2` → one in flight, two queued, next dropped and counted, `submit` does not block; `shutdownNow` returns promptly while the counter hangs; `UsageRecordingIntegrationTest` (`@SpringBootTest(RANDOM_PORT)` + `@ServiceConnection` Redis container): N requests → `quota:used:{id}:{p}` reaches N (bounded polling helper, no Awaitility dependency), `400` requests add nothing; `UsageRecordingWithoutRedisTest`: Redis host pointing at a closed port → responses `200`, failed count grows, context closes promptly; `StartupFailureTest` case for `USAGE_QUEUE_CAPACITY=0`; `ArchitectureTest` unchanged and passing | Spring Boot test, Testcontainers |

The period-boundary scenario ("event processed after the boundary") is covered at the adapter level by applying an event with an explicit older period index, and at the app level by a fixed clock; no test waits for a real boundary.

## Risks / Trade-offs

- [Redis down: every event costs up to the 1 s timeout, so the queue fills and most events are dropped] → Accepted by FR-8/D7a ("nothing is counted" during an outage). Connection-refused fails fast; only blackholed hosts hit the timeout.
- [Single writer throughput limits recording rate to ~1 / RTT (≈ thousands/s locally)] → Enough for the prototype (NFR-7); architecture §8 names batching as the production path. Overflow is dropped and counted, never blocks requests.
- [Events lost on shutdown or restart] → Accepted (spec, requirements §7 on restart). Daemon thread + bounded join keeps shutdown fast.
- [Existing bootstrap tests run without Redis and now trigger recording attempts] → They only assert HTTP responses; failures are swallowed by the writer. Short connect timeout keeps context shutdown quick.
- [Tests needing Docker in `:adapters:infra` and `:bootstrap`] → Already a project requirement (NFR-9); `./gradlew build` now needs Docker, documented in `gateway/README.md`.
- [Counters only readable in memory until item 6 logs them] → Exposed via `UsageRecorder.stats()` for tests and for item 6 to log; acceptable intermediate state.

## Migration Plan

No data migration: Redis keys are new and expire on their own. Rollback is reverting the change; leftover keys disappear after two periods.
