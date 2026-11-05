# Design

## Context

See proposal.md (Why). Current state in the gateway:

- `UsageRecorder` (`:app`, `app.usage`) submits events to a single-thread `ThreadPoolExecutor` (`UsageWriterExecutor`, bounded `ArrayBlockingQueue`, `AbortPolicy`). A full queue increments `dropped`; a `UsageRecordingFailedException` from the `UsageCounter` port increments `failed`; success increments `recorded` and notifies `RecordedUsageListener` (`BlockTenantsOverLimit`). Stats are exposed as `UsageRecordingStats(recorded, dropped, failed)`. Nothing is logged.
- `RedisUsageCounter` (`:adapters:infra`) wraps every `DataAccessException` into `UsageRecordingFailedException`. Lettuce uses `spring.data.redis.timeout` and `connect-timeout` of `1s` and connects lazily.
- `application.yaml` disables the Redis health indicator, hides details and disables probes. The `Dockerfile` `HEALTHCHECK` curls `/actuator/health`.
- Redis-down behaviour of the request path is already covered (`BlockedTenantWhileRedisDownTest`, `UsageRecordingWithoutRedisTest`); blocked-set enforcement is unchanged by this change.
- Spring Boot 4.1 (modular Actuator; the Redis health indicator ships with the Data Redis module and is switched by `management.health.redis.enabled`).

## Goals / Non-Goals

**Goals:**
- Redis as a health component with a bounded check time; overall health `DOWN`/`503` during an outage, probes `UP` (decision taken with the user for this change).
- Log the outage once at start and once at recovery, without per-event log lines and without touching the request thread.
- Prove the full outage cycle with Redis stopped mid-run in a Testcontainers test.

**Non-Goals:**
- No change to the quota decision, the blocked set or the record script.
- No tracking of events lost during an outage: no periodic report, no counts in log lines, no new setting. Enforcement during the outage is the current `BlockedTenants` state only. The existing in-memory `UsageRecordingStats` stay untouched and unlogged.
- No metrics endpoint, Micrometer meters or alerting for dropped/failed counts.
- No dashboard changes; the nginx proxy already forwards `/actuator/health` responses unchanged, including `503`.

## Decisions

### 1. Health uses Actuator's built-in Redis indicator, wired only in configuration
Enable `management.health.redis.enabled`, set `management.endpoint.health.show-components: always`, keep `show-details: never`, and enable `management.endpoint.health.probes.enabled: true`. The default `liveness` (`livenessState`) and `readiness` (`readinessState`) groups do not include Redis, so NFR-4 holds without custom groups. Default status mapping gives `503` for `DOWN`.

- Layer: infrastructure / framework only (`:bootstrap` `application.yaml`). Health is an operational concern of the running process, not a use case, so no port or domain type is added.
- Check time: the indicator issues a `PING` through the shared Lettuce connection factory, so it is bounded by the existing `spring.data.redis.timeout` / `connect-timeout` (`1s`). No separate health timeout setting.
- Alternatives: a custom `HealthIndicator` backed by an app port that reports the writer's outage state (rejected: it would only report after traffic fails, so an idle gateway would look healthy with Redis down; and it duplicates a built-in). Custom aggregation keeping overall `UP` (rejected by the user in favour of `503`).
- Explicit groups `management.endpoint.health.group.liveness.include=livenessState` and `...readiness.include=readinessState` are set anyway, so a future auto-configured indicator cannot slip into a probe.

### 2. Container healthcheck on liveness
`gateway/Dockerfile` `HEALTHCHECK` curls `/actuator/health/liveness`. Start scripts (`docker compose up --wait`) then wait for a gateway that is up whether or not Redis is, and stopping Redis never makes the container unhealthy. `docker-compose.yml` and the dashboard nginx config need no change.

### 3. Outage transitions tracked by a small app-layer state object
New `RecordingOutage` in `:app` `app.usage` (plain Java, unit-tested): a two-state flag. `failed()` returns `true` only when this failure starts an outage; `succeeded()` returns `true` only when this success ends one. It holds no counts. It is owned by `UsageRecorder` and only touched on the writer thread, so it needs no synchronisation.

`UsageRecorder.apply` calls it and reports transitions to a new outbound port `UsageRecordingAlerts` (`:app`, `app.usage`, next to `UsageCounter`, per the hexagonal rule for non-repository ports):
- `recordingFailing(UsageRecordingFailedException cause)`
- `recordingResumed()`

Initial state is "not failing", so the first failure after startup without Redis logs the warning. Events that fail are discarded as today; nothing about them is accumulated for reporting. Alternatives: a `UsageCounter` decorator detecting transitions (rejected: a second place holding writer state for no gain); logging straight from `UsageRecorder` with SLF4J (rejected: `:app` must stay framework-free, enforced by `ArchitectureTest`); a periodic lost-event report (rejected by decision for this change: lost events are not tracked, enforcement relies on `BlockedTenants` only).

### 4. SLF4J adapter in `:adapters:infra`
`Slf4jUsageRecordingAlerts` in `adapters.infra.logging` implements `UsageRecordingAlerts`:
- `WARN` `Redis unavailable, usage recording failing: no new tenants are blocked and counting is paused ({cause message})`
- `INFO` `Redis available again, usage recording resumed`

The cause message is that of the root `DataAccessException`; no stack trace (the outage line must stay one line). Wired as a `@Bean` in `InfraConfiguration`; `AppConfiguration` passes it to `UsageRecorder`.

### 5. Tests
- App unit tests (no Spring): `RecordingOutageTest`, `UsageRecorderTest` additions (one alert per outage, one resume per recovery, no alert for drops, `submit` never calls alerts).
- Adapter test: `Slf4jUsageRecordingAlertsTest` with a Logback `ListAppender` (levels, cause message, no stack trace, no counts).
- Spring/Testcontainers (`:bootstrap`):
  - `HealthEndpointTest` moves to a Testcontainers Redis: `200` `UP`, `components.redis.status` `UP`, no details; liveness/readiness `UP` without a `redis` component.
  - `HealthWithoutRedisTest` (closed port): `503` `DOWN` with Redis `DOWN`, probes `200` `UP`, startup succeeds.
  - `RedisStoppedMidRunTest`: Redis container, `MutableClock`, Bronze limit `5`; run traffic, block tenant `1`, give tenant `2` `used` `3`, **pause** the container, then assert: tenant `1` gets `429`, tenant `2` gets `10 × 200` and is not blocked, health `503` within 2 s while probes are `UP` within 1 s, exactly one outage warning and no lost-event counts in the log (`OutputCaptureExtension`); a period rollover during the outage unblocks tenant `1`; **unpause**, then health `UP`, `quota:used:2:{p}` at least `3` (record calls that timed out during the pause are applied late, see Risks), one resume line is logged, and tenant `2` is blocked again once a recorded `used` reaches `5`.
  - `BusinessEndpointTest`, `ServerPortTest`: switch to `/actuator/health/liveness` (they run without Redis).
- Pause rather than `docker stop` simulates "Redis stopped": stopping and starting a Testcontainers container changes its mapped port, which the `@ServiceConnection` bound at context start would not follow. Pause exercises the harder case (timeouts instead of refused connections); the refused case is covered by `HealthWithoutRedisTest` and the existing `UsageRecordingWithoutRedisTest`.

### Layer map

| Element | Module / layer | Uses |
|---|---|---|
| `RecordingOutage` | `:app` application (`app.usage`) | — |
| `UsageRecordingAlerts` (port) | `:app` application (`app.usage`) | — |
| `UsageRecorder` (changed) | `:app` application | `UsageCounter`, `RecordedUsageListener`, `UsageRecordingAlerts` |
| `Slf4jUsageRecordingAlerts` | `:adapters:infra` (`adapters.infra.logging`) | SLF4J |
| Redis health indicator, probes | `:bootstrap` infrastructure (config only) | Actuator, Lettuce |
| `HEALTHCHECK` | `gateway/Dockerfile` | `/actuator/health/liveness` |

## Risks / Trade-offs

- [Every dashboard health poll (5 s) sends a `PING` to Redis] → Not on the request path and cheap; acceptable for the prototype. Production direction (D10) is separate Redis monitoring.
- [Health `PING` shares the Lettuce connection with the writer; during a hang, health waits up to the 1 s command timeout] → Bounded by the spec's 2 s; probes do not touch Redis.
- [Tests without Redis default to `localhost:6379`, which may be a real Redis on a developer machine] → Health-down tests use an explicitly closed port; other no-Redis tests assert liveness only.
- [The outage warning depends on traffic: an idle gateway logs nothing while Redis is down] → Accepted (spec "No traffic, no log"); health still reports `DOWN`.
- [`/actuator/health` `503` may be read by clients as "gateway down"] → Only the dashboard polls it; `dashboard-polling-and-states` must parse the `503` body as "enforcement degraded", not count it as an unreachable gateway (snapshot polls decide that per FR-21). Container and script health use liveness.
- [A hanging Redis (paused process, network stall) still accepts the writer's commands into its socket buffer; calls that time out on the gateway are executed when Redis answers again, so usage can grow after recovery by events the writer counted as failed] → Accepted by decision for this change: no retry, cancel or reconnect-on-timeout logic. The effect is bounded by the events sent during the hang and only makes enforcement stricter after recovery. With Redis stopped (refused connections) nothing is applied late. Found while implementing `RedisStoppedMidRunTest` (`used` became `13`, not `3`).
- [Drops (queue full) are never logged, so a slow but reachable Redis that overflows the queue goes unnoticed in the logs] → Accepted by decision for this change (deviation from FR-6b's "logged" drops); the in-memory dropped count still exists for a later metric if needed.

## Migration Plan

No data migration. The contract change is limited to `/actuator/health` returning `503` while Redis is down; the Docker `HEALTHCHECK` moves to liveness in the same change so `start`/`start-gateway` scripts keep returning without Redis. Rollback: revert the change.
