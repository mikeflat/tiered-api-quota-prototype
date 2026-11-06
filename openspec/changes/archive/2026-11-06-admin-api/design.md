# Design

## Context

See proposal.md (Why) and the specs in this change for the observable contract. Current state of the gateway (Gradle modules `:domain`, `:app`, `:adapters:api`, `:adapters:infra`, `:adapters:persistence`, `:bootstrap`; ArchUnit rules in `ArchitectureTest`):

- `TenantRegistry` (`:domain`, `domain.tenant`) is a `ConcurrentHashMap<Long, Tenant>` with `find`, `listByName` (case-insensitive name, then id), `countByTier`. No update, no filtering. `Tenant.withTier(Tier)` already exists. `Tier.parse` is case-insensitive.
- `BlockedTenants` (`:domain`, `domain.quota`) holds one `PeriodBlocks(periodIndex, Set)` in an `AtomicReference`; `isBlocked` is lock-free, `block` swaps in an empty set for a newer period. No removal.
- `QuotaSettings` (`:domain`) has `periodIndexAt`, `secondsUntilNextPeriod`, `limitOf`, `limitsOf` → `UsageLimits(limit, nearBoundary)`.
- `UsageRecorder` (`:app`) resolves `UsageLimits` per event via `TenantUsageLimits` on the writer thread, calls `UsageCounter.record`, then `RecordedUsageListener` = `BlockTenantsOverLimit`, which blocks when `limits.reachedBy(used)` and the event's period is current.
- `RedisUsageCounter` (`:adapters:infra`, `adapters.infra.redis`) owns the key builders (`usedKey`, `rejectedKey`, `secondKey`, `periodTotalKey`, `nearKey`, `overKey`) and the record script. Lettuce timeouts are 1 s.
- `:adapters:api` generates interfaces/DTOs from `openapi/gateway-api.yaml` (`interfaceOnly`, `useBeanValidation`, Spring Boot 4, `ProblemDetail` mapped to Spring's class). `spring.mvc.problemdetails.enabled=true`, so Spring MVC errors (type mismatch, unreadable body, method validation, `ResponseStatusException`) are already rendered as `application/problem+json`.
- Beans are declared per module in `DomainConfiguration`, `AppConfiguration`, `InfraConfiguration`, `ApiConfiguration`, `PersistenceConfiguration`; no component scanning of modules. The writer thread comes from `UsageWriterExecutor` in `:bootstrap`.
- `HealthEndpointTest` asserts `GET /admin/snapshot` is `404`; it must change with this work.

## Goals / Non-Goals

**Goals:**
- Implement the three admin endpoints with the component names and flows of architecture §2.1 and §6 (`AdminController`, `MetricsSnapshotJob`, `QuotaPeriod`, `TenantRegistry` filtering and tier update).
- Keep `:domain` and `:app` free of Spring/Redis; Redis reads behind application ports implemented in `:adapters:infra`.
- Make tier change and blocking race-free without adding I/O or locks to the request path.

**Non-Goals:**
- No change to the record Lua script, the Redis key model, the request path or health.
- No dashboard work; no persistence of tier changes.
- No logging of snapshot read failures (Redis outages are already logged once by the writer and reported by health).

## Decisions

### 1. Module and layer map

| Layer / module | Package | New or changed type | Role |
|---|---|---|---|
| Domain `:domain` | `domain.tenant` | `TenantFilter` (record: `nameContains`, `id`, `tier`, each optional; `matches(Tenant)`; `none()`) | Pure filter rule |
| | `domain.tenant` | `TenantRegistry.search(TenantFilter)`, `TenantRegistry.changeTier(long, Tier) → Optional<Tenant>` | Filtering + in-memory tier update |
| | `domain.quota` | `TenantStatus` (enum `OK`, `NEAR`, `OVER`; `of(long used, UsageLimits)`) | Status rule shared by rows and tests |
| | `domain.quota` | `QuotaPeriod` (record `index`, `endsAt`, `secondsLeft`), `QuotaSettings.periodAt(Instant)` | Architecture's `QuotaPeriod` |
| | `domain.quota` | `TrafficMetrics`, `SecondTraffic`, `PeriodTotals` (records) | Value of the cached global part |
| | `domain.quota` | `BlockedTenants.unblock(long)`, `BlockedTenants.blockIf(long, long, BooleanSupplier)` | See decision 3 |
| Application `:app` | `app.admin` | `ListTiers`, `TierLimit` | Inbound port `GET /admin/tiers` |
| | `app.admin` | `ChangeTenantTier`, `TierChange` (sealed: `Changed(Tenant, long limit)`, `TenantNotFound`) | Inbound port `PUT …/tier` |
| | `app.metrics` | `TrafficMetricsReader` (outbound port), `MetricsReadFailedException` | Read global metrics |
| | `app.metrics` | `LatestTrafficMetrics` (in-memory cache with max age), `RefreshTrafficMetrics` (`refresh()`) | Architecture's `MetricsSnapshotJob` logic |
| | `app.snapshot` | `TenantUsageReader` (outbound port), `TenantUsage(used, rejected)`, `UsageReadFailedException` | Read page usage and status sets |
| | `app.snapshot` | `PageRequest` (`page ≥ 0`, `1 ≤ size ≤ 100`, `offset()`), `SnapshotQuery(TenantFilter, Optional<TenantStatus>, PageRequest)` | Query input with invariants |
| | `app.snapshot` | `BuildAdminSnapshot`, `AdminSnapshot`, `TenantRows` (sealed: `Available(rows, total, page)`, `Unavailable(page)`), `TenantRow(Tenant, long limit, Optional<RowUsage>)`, `RowUsage(used, rejected, TenantStatus)` | Inbound port `GET /admin/snapshot` |
| | `app.quota` | `BlockTenantsOverLimit` uses `blockIf` with a current-limit re-check | Decision 3 |
| Adapter out `:adapters:infra` | `adapters.infra.redis` | `RedisKeys` (key builders moved out of `RedisUsageCounter`) | One source of key names for writer and readers |
| | `adapters.infra.redis` | `RedisTrafficMetricsReader implements TrafficMetricsReader` | One pipelined round trip |
| | `adapters.infra.redis` | `RedisTenantUsageReader implements TenantUsageReader` | `MGET` for the page; `SMEMBERS`/`SUNION` for status |
| Adapter in `:adapters:api` | `adapters.api.web` | `AdminController implements AdminApi` (generated), `AdminDtoMapper`, `AdminParameters` (parse tier/status) | HTTP translation only |
| | resources | `openapi/gateway-api.yaml`: `Admin` tag, three paths, DTO schemas | Contract |
| Infrastructure `:bootstrap` | `bootstrap.config` | Beans in the existing per-module configurations; `MetricsRefreshScheduler` (single daemon thread `metrics-refresh`, fixed rate 1 s, `shutdownNow` on close) | Timer wiring, like `UsageWriterExecutor` |

The new read ports are not repositories of domain objects, so they are application ports next to their use cases (hexagonal rule); their implementations are in `:adapters:infra`. `RefreshTrafficMetrics` and `BuildAdminSnapshot` take a `java.time.Clock` like the existing use cases.

- Alternative: one `AdminService` facade for all three endpoints (rejected: three small use cases keep each test focused and match the existing one-class-per-use-case style).

### 2. Tier change (`ChangeTenantTier`)
`change(long tenantId, Tier tier)`:
1. `registry.changeTier(id, tier)` — `ConcurrentHashMap.computeIfPresent(id, (k, t) -> t.withTier(tier))`; empty → `TenantNotFound`.
2. `blockedTenants.unblock(id)`.
3. Return `Changed(tenant, settings.limitOf(tier))`.

Registry first, unblock second: after the unblock, every event the writer applies resolves the new limit (`TenantUsageLimits` reads the registry per event). The same tier again is treated like any change (registry unchanged, tenant unblocked; its next event re-blocks it if it is still over). No Redis call, so it works during an outage.

The controller parses the body's `tier` with `Tier.parse` (case-insensitive) and maps `IllegalArgumentException` to `400`; a non-integer path id fails Spring's type conversion → `400`; `TenantNotFound` → `404`. Both via `ResponseStatusException`, which the existing problem-details setup renders as `application/problem+json`.

### 3. Race between blocking and tier change (`BlockedTenants.blockIf`)
Without care, the writer can resolve the old (lower) limit, get `used ≥ old limit` back from Redis, and add the tenant to the blocked set right after the admin thread unblocked it; the tenant then stays blocked until the period ends, since rejected events never unblock.

Fix: writes to `BlockedTenants` (`blockIf`, `unblock`) are `synchronized` on the instance; `isBlocked` stays lock-free (reads the `AtomicReference` and a concurrent set). `BlockTenantsOverLimit` keeps its current checks (allowed, current period, `limits.reachedBy(used)`) and then calls `blockIf(id, p, () -> tenantUsageLimits.of(id).map(l -> l.reachedBy(used)).orElse(false))`, so the condition is re-evaluated with the registry's current tier inside the lock. Because `ChangeTenantTier` updates the registry before taking the lock to unblock, either the block happens first and is undone, or the re-check already sees the new tier.

The status written by the Lua script for that one event still reflects the old limit; it is corrected by the tenant's next event (docs/02 §7 already accepts this).

- Alternative: run the unblock on the writer thread (rejected: the writer queue can be full or slow during a Redis outage, and FR-11 requires tier changes to work then).
- Alternative: a tier version stamp on `Tenant` compared at block time (rejected: more moving parts for the same guarantee).
- Cost: one uncontended lock per block (at most once per tenant per period) and per tier change; nothing on the request path.

### 4. Global metrics: background refresh and cache (`MetricsSnapshotJob`)
`RefreshTrafficMetrics.refresh()` runs every second on the `metrics-refresh` thread: `now = clock.instant()`, `p = periodIndexAt(now)`, last complete second `s = now − 1`; calls `TrafficMetricsReader.read(p, s)`; on success stores `TrafficMetrics` with `readAt = now` in `LatestTrafficMetrics`; on `MetricsReadFailedException` marks it unavailable. Any other exception is caught in the scheduler wrapper so the fixed-rate task keeps running.

`LatestTrafficMetrics.current(Instant now)` returns `Optional.empty()` when unavailable or when `now − readAt > 3 s` (covers a hung read or a stalled thread). Snapshot requests only read this cache (NFR-3).

`RedisTrafficMetricsReader` issues in one pipeline: `MGET` of `traffic:allowed:{s-59..s}`, `MGET` of `traffic:rejected:{s-59..s}`, `MGET traffic:total:allowed:{p} traffic:total:rejected:{p}`, `SCARD quota:near:{p}`, `SCARD quota:over:{p}` (architecture §6). Missing values are `0`. `DataAccessException` → `MetricsReadFailedException`.

- Alternative: a read-only Lua script returning everything (rejected: architecture specifies a pipeline; the script would need all 124 key names passed anyway).
- Alternative: Spring `@Scheduled` (rejected: would need `@EnableScheduling` and annotations near use cases; an explicit executor matches `UsageWriterExecutor` and keeps `:app` Spring-free).

### 5. Snapshot rows (`BuildAdminSnapshot`)
For a `SnapshotQuery` at `now`:
1. `period = settings.periodAt(now)`; `metrics = latestTrafficMetrics.current(now)`.
2. `candidates = registry.search(filter)` (sorted by name).
3. With a status filter: `flagged = usageReader.flaggedTenants(p, statuses)` where `NEAR → {NEAR}` (`SMEMBERS quota:near:{p}`), `OVER → {OVER}` (`SMEMBERS quota:over:{p}`), `OK → {NEAR, OVER}` (`SUNION`, one round trip); keep members for near/over, non-members for ok. On `UsageReadFailedException` → `TenantRows.Unavailable(page)`.
4. `total = candidates.size()`; `pageRows = sublist(offset, offset + size)` (empty past the end, no Redis read).
5. `usageReader.usageOf(p, ids)` → one `MGET` of `quota:used:{id}:{p}` and `quota:rejected:{id}:{p}` for the page. Status per row = `TenantStatus.of(used, settings.limitsOf(tier))`. On failure: without status filter → rows with empty usage; with status filter → `Unavailable(page)` (FR-15 says status-filtered rows are unavailable without Redis).

The tenant's limit and tier are taken from the same `Tenant` snapshot returned by `search`, so a row is self-consistent even if a tier change happens during the request.

Cost per request: at most 2 round trips (status set + page `MGET`), independent of tenant count except the in-memory registry scan (15 tenants; at 50,000 tenants this moves to an index, architecture §8).

### 6. HTTP contract (`gateway-api.yaml`, `AdminController`)
- `GET /admin/tiers` → `TierLimit[]` (`name`, `limit`).
- `PUT /admin/tenants/{id}/tier`, body `TierChangeRequest { tier: string }` (required) → `TenantTier { id, name, tier, limit }`; `400`/`404` `ProblemDetail`.
- `GET /admin/snapshot?name&id&tier&status&page&size` → `AdminSnapshot { period: QuotaPeriod, metrics: TrafficMetrics|null, tenants: TenantPage }`; `TenantPage { rowsAvailable, rows: TenantRow[]|null, total: int|null, page, size }`; `TenantRow { id, name, tier, limit, used|null, rejected|null, status|null }`; `TrafficMetrics { perSecond: SecondTraffic[], totals: PeriodTotals, tenantsNear, tenantsOver }`; `QuotaPeriod { index, endsAt (date-time), secondsLeft }`.
- `id` is `int64` with `minimum: 1`, `page` `int32` `minimum: 0` default `0`, `size` `int32` `minimum: 1` `maximum: 100` default `10`; generated bean-validation annotations plus Spring MVC's built-in method validation give `400` problem details; non-numeric values fail type conversion → `400`. `tier` and `status` are plain strings parsed by `AdminParameters` (case-insensitive, blank → absent) because the generated enums would be case-sensitive; unknown values → `ResponseStatusException(400)`.
- Nullable DTO fields are marked `nullable: true`; Jackson writes `null` explicitly, which the dashboard relies on to show "Metrics unavailable".
- Admin paths are outside `/api/*`, so `QuotaFilter` does not run (FR-18) — no change needed.

### 7. Key builders move to `RedisKeys`
`RedisUsageCounter`'s public static key builders move to a final class `RedisKeys` in the same package; `RedisUsageCounter`, the two readers and tests use it. Pure refactor, no key name changes (architecture §4 unchanged).

## Risks / Trade-offs

- [Snapshot rows read Redis on the request thread; a hung Redis delays the answer by the 1 s command timeout] → Bounded by `spring.data.redis.timeout` (1 s), below the dashboard's 2 s poll timeout; the global part never waits.
- [Status filter and row status can disagree right after a tier change (tenant still in the over set until its next event)] → Accepted by docs/02 §7 and specified in the admin-snapshot spec scenario "Upgraded tenant still in the over set".
- [Global metrics up to ~1 s old plus the age of the last complete second] → Within NFR-6 (≤ 2 s); the 3 s max age turns a stalled refresh into "unavailable" instead of stale numbers.
- [Lock in `BlockedTenants` writes] → Only on block (≤ once per tenant per period) and tier change; `isBlocked` on the request path stays lock-free.
- [Registry scan per snapshot is O(tenants)] → Fine for 15 tenants (NFR-7); production direction in architecture §8.
- [Writer and snapshot share Lettuce's connection] → Reads are a few commands per second; no pool needed for the prototype.

## Migration Plan

No data migration: Redis keys and the record script are unchanged, tier changes are in memory. Deploy by rebuilding the gateway image; rollback by redeploying the previous image (tier changes are lost either way on restart, as specified).
