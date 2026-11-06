# Design

## Context

See proposal.md (Why). Current state in the gateway:

- `UsageRecorder` (`:app`, `app.usage`) runs each event on the single writer thread: `UsageCounter.record(UsageEvent)` returns `used`, then `RecordedUsageListener.usageRecorded(event, used)` is called. The only listener is `BlockTenantsOverLimit` (`:app`, `app.quota`), which looks the tenant up in `TenantRegistry` and calls `QuotaSettings.reachesLimit(tier, used)`.
- `RedisUsageCounter` (`:adapters:infra`, `adapters.infra.redis`) calls `redis/record-usage.lua` with `KEYS = [used, rejected]`, `ARGV = [outcome, ttlSeconds]` (TTL = two periods, from `InfraConfiguration`). The script sets the TTL when `INCR` returns `1` and returns `used`.
- `QuotaSettings` (`:domain`) already has `limitOf(Tier)` and `nearBoundary(limit)` (smallest `n` with `n × 100 ≥ L × T`), unused so far outside tests except `reachesLimit`.
- `TenantRegistry` never removes tenants; tier updates arrive with backlog item 8.
- Redis image is `redis:8.10-alpine` (compose and Testcontainers).

## Goals / Non-Goals

**Goals:**
- Extend the one record call with the keys of architecture §4 (no new round trip, no new thread).
- Resolve the tenant's limit and near boundary once per event on the writer thread and use the same values for the script and for blocking.
- Keep `:domain` and `:app` framework-free; Redis key names and script stay in `:adapters:infra`.

**Non-Goals:**
- No reader of the new keys (snapshot job, status filter), no `TenantStatus` type for rows yet (backlog items 9, 10 add it where they need it).
- No tier-change operation on `TenantRegistry` (item 8).
- No new configuration setting: the per-second TTL is the fixed 120 s of architecture §4.

## Decisions

### 1. Domain value `UsageLimits` (`:domain`, `domain.quota`)
A record `UsageLimits(long limit, long nearBoundary)` with invariants `limit ≥ 1`, `1 ≤ nearBoundary ≤ limit`, and `reachedBy(long used)` (`used ≥ limit`). `QuotaSettings.limitsOf(Tier)` builds it from `limitOf` and `nearBoundary`. `QuotaSettings.reachesLimit` is removed once its last caller moves to `UsageLimits` (it has no other production caller).

- Why: the script and the blocking decision must see the same pair of numbers; one value object passed through makes that explicit instead of two lookups that could diverge if a tier changes in between.
- Alternative: pass `Tier` to the port and let the adapter compute limits (rejected: quota rules would leak into the Redis adapter).

### 2. Limits resolved on the writer thread, at write time (`:app`)
New app class `TenantUsageLimits` (`app.usage`), built from `TenantRegistry` and `QuotaSettings`, with `Optional<UsageLimits> of(long tenantId)` (pure in-memory lookup). `UsageRecorder` takes it as a constructor dependency and, in `apply(event)`:

1. resolves `limits`; if the tenant is absent (cannot happen today: the filter admits only registry tenants and the registry never removes any) the event is skipped without a Redis call and without touching the counts;
2. calls `counter.record(event, limits)`;
3. calls `listener.usageRecorded(event, used, limits)`.

Resolving at write time (not at request time) matches architecture §5 ("current limit" passed to `EVALSHA`) and FR-11: after a tier change the next applied event uses the new limit. The request thread is unchanged.

- Alternative: capture the limit in `UsageEvent` at request time (rejected: events already queued before a tier change would evaluate against the old tier, and the event would carry quota policy it does not own).
- Alternative: let `UsageRecorder` take `TenantRegistry` and `QuotaSettings` directly (rejected: a small named collaborator keeps `UsageRecorder` about queueing/outage and is unit-testable on its own).

### 3. Port signatures (`:app`)
- `UsageCounter.record(UsageEvent event, UsageLimits limits)` → `long used` (outbound application port, unchanged location `app.usage`).
- `RecordedUsageListener.usageRecorded(UsageEvent event, long used, UsageLimits limits)`.
- `BlockTenantsOverLimit` drops its `TenantRegistry` dependency and blocks when `limits.reachedBy(used)` for an allowed event of the current period; the rest of its logic is unchanged.

All test doubles of `UsageCounter` (`UsageRecorderTest`, `QuotaOvershootTest`, `UsageWriterExecutorTest`, `UsageRecordingWithoutRedisTest`) follow the new signature.

### 4. Record script (`:adapters:infra`, `redis/record-usage.lua`)
Keys passed by `RedisUsageCounter`, all derived from the event (no key built inside the script, so every touched key is declared):

| # | Key |
|---|---|
| 1 | `quota:used:{tenantId}:{p}` |
| 2 | `quota:rejected:{tenantId}:{p}` |
| 3 | `traffic:allowed:{s}` or `traffic:rejected:{s}` (by outcome) |
| 4 | `traffic:total:allowed:{p}` or `traffic:total:rejected:{p}` (by outcome) |
| 5 | `quota:near:{p}` |
| 6 | `quota:over:{p}` |

`ARGV = [outcome, periodTtlSeconds, secondTtlSeconds, limit, nearBoundary, tenantId]`.

Steps, atomically:
1. Allowed: `INCR` key 1 → `used`. Rejected: `INCR` key 2, `used = GET key 1 or 0`. Counters keep the existing "set TTL when value is `1`" pattern (period TTL for 1, 2, 4; second TTL for 3).
2. `INCR` keys 3 and 4.
3. Status: `used ≥ limit` → `SADD over`, `SREM near`; `used ≥ nearBoundary` → `SADD near`, `SREM over`; else `SREM` both. After an `SADD`, `EXPIRE <set> periodTtl NX` (Redis ≥ 7) sets the TTL only when the set has none, which also covers a set recreated after it emptied (Redis deletes empty sets).
4. Return `used`.

- The status rule lives in Lua because it must be atomic with the counter update (FR-12); the boundary itself is computed in the domain (`QuotaSettings.nearBoundary`) and passed in, so the threshold arithmetic is not duplicated.
- Alternative: `MULTI` from Java with several commands (rejected: needs `used` before deciding membership, i.e. two round trips or a race).
- Alternative: pass all four traffic keys and choose in Lua (rejected: two unused keys per call, no benefit).

### 5. `RedisUsageCounter` (`:adapters:infra`)
Gains static key builders `secondKey(outcome, epochSecond)`, `periodTotalKey(outcome, p)`, `nearKey(p)`, `overKey(p)` next to `usedKey` / `rejectedKey`, for tests now and the snapshot reader later. Constructor stays `(template, script, periodTtlSeconds)`; the per-second TTL is a constant `SECOND_KEY_TTL_SECONDS = 120`. Failure mapping to `UsageRecordingFailedException` is unchanged.

### 6. Wiring (`:bootstrap`)
`AppConfiguration` adds a `TenantUsageLimits` bean and passes it to `UsageRecorder`; `BlockTenantsOverLimit` loses the registry argument. `InfraConfiguration` is unchanged (same script resource and period TTL).

### 7. Tests
- Domain: `UsageLimitsTest` (invariants, `reachedBy`), `QuotaSettingsTest` (`limitsOf` per tier, e.g. Gold `10000` / `8000`).
- App: `TenantUsageLimitsTest`; `UsageRecorderTest` (limits passed to counter and listener, unknown tenant skipped, Redis failure still counted as failed); `BlockTenantsOverLimitTest` and `QuotaOvershootTest` on the new signature.
- Adapter (Testcontainers Redis): `RedisUsageCounterTest` extended for per-second and period totals, TTLs (120 s regardless of period, two periods for totals and sets, `NX` on later members and on a recreated set), near/over transitions incl. overshoot, rejected events keeping status, rejected without usage, late events, "status follows the limit passed in" (seeded `used` `10` in `over`, limits `100/80` → neither set), and "only expected keys" on an empty database.
- Integration (`:bootstrap`, Testcontainers, fixed clock): `TrafficMetricsRecordingIntegrationTest` sends requests over HTTP for two tenants until one is blocked and checks per-second keys, period totals and set membership after the writer has drained.

## Risks / Trade-offs

- [Every request now writes two global keys (hot keys)] → Fine for one Redis at prototype rate; production direction (sharded or per-instance aggregated counters) is already in architecture §8 and out of scope.
- [Script mixes per-tenant and global keys, not Cluster-safe] → Accepted for single Redis; architecture §8 describes the split.
- [`EXPIRE … NX` needs Redis 7+] → Compose and tests pin `redis:8.10-alpine`; documented in the README.
- [Status rule in Lua and blocking rule in Java could drift] → Both take the same `UsageLimits`; the integration test checks that the tenant in `quota:over:{p}` is also blocked.
- [Near/over sets stale after a tier change until the next event] → Accepted limitation (docs/02 §7), spelled out in the spec.
- [Slightly longer script per event increases writer time and therefore overshoot] → A few extra in-memory Redis commands per call; overshoot test stays as is and must still pass.

## Migration Plan

No data migration: old keys expire within two periods; new keys appear on the first event after deploy. Rollback = previous gateway image; the new keys are then simply unused and expire.
