# Design

## Context

See proposal.md for motivation and specs/quota-enforcement/spec.md and specs/usage-recording/spec.md for the behaviour.

Current gateway state (observed in code after item 4):

- `QuotaFilter` (`:adapters:api`) parses `X-Tenant-Id`, calls `AdmitBusinessCall.admit(long)` (`:app`) and switches on the sealed `Admission` (`Admitted(Tenant)`, `TenantUnknown`) from `:domain`. A `400` is written by `ProblemResponseWriter` as `application/problem+json`.
- `AdmissionPolicy` (`:domain`) only looks the tenant up in `TenantRegistry`.
- `AdmitBusinessCall` reads `Clock` once and, on `Admitted`, submits `UsageEvent.allowedAt(...)` to `UsageRecorder`.
- `UsageRecorder.apply` (runs on the single `usage-writer` thread) calls `UsageCounter.record(event)`, which already returns `used` for both outcomes, and discards the value.
- `QuotaSettings` holds period, tier limits and near threshold and computes `periodIndexAt(epochSecond)`.
- `ArchitectureTest` forbids Spring in `:domain` and `:app`; JDK concurrency types are allowed.
- `UsageRecordingIntegrationTest` overrides `Clock` with a `@Primary` fixed clock and uses a Testcontainers Redis with `@ServiceConnection`.

## Goals / Non-Goals

**Goals:**
- Request-path cost stays one registry lookup, one volatile read and one set lookup, plus the existing `Executor.execute`.
- No scheduled reset: the period index carried with the set decides whether it still applies.
- The decision rules (reaches limit, stale period, Retry-After) are pure domain code, unit-tested without Spring or Redis.
- Item 8 can add `unblock(tenantId)` to the blocked set and item 6 can add logging without reshaping this design.

**Non-Goals:**
- Lock-free perfection under every interleaving at a period boundary: a few requests of imprecision around the boundary are inside the accepted overshoot.
- Passing the limit into Lua or maintaining near/over sets (item 7).

## Decisions

### D-1. Layer placement

| Element | Layer / module | Package | Kind |
|---|---|---|---|
| `BlockedTenants` | domain `:domain` | `domain.quota` | thread-safe per-period set: `isBlocked(long tenantId, long periodIndex)`, `block(long tenantId, long periodIndex)` |
| `Admission.Blocked(Tenant tenant, long retryAfterSeconds)` | domain | `domain.quota` | new case of the sealed `Admission` |
| `AdmissionPolicy` (changed) | domain | `domain.quota` | `admit(long tenantId, long epochSecond)`; uses `TenantRegistry`, `BlockedTenants`, `QuotaSettings` |
| `QuotaSettings.secondsUntilNextPeriod(long epochSecond)` | domain | `domain.quota` | `(p + 1) × periodSeconds − epochSecond`, always ≥ 1 |
| `QuotaSettings.reachesLimit(Tier tier, long used)` | domain | `domain.quota` | `used ≥ limitOf(tier)` |
| `UsageEvent.rejectedAt(Tenant, Instant, QuotaSettings)` | domain | `domain.quota` | factory next to `allowedAt` |
| `RecordedUsageListener` | application `:app` | `app.usage` | `void usageRecorded(UsageEvent event, long used)`; called by `UsageRecorder` after a successful record |
| `UsageRecorder` (changed) | application | `app.usage` | constructor gains a `RecordedUsageListener` |
| `BlockTenantsOverLimit` | application | `app.quota` | use case implementing `RecordedUsageListener`; uses `TenantRegistry`, `QuotaSettings`, `BlockedTenants`, `Clock` |
| `AdmitBusinessCall` (changed) | application | `app.quota` | passes the arrival second to the policy; submits allowed or rejected event |
| `QuotaFilter` (changed) | adapter `:adapters:api` | `adapters.api.filter` | `Blocked` → `429`, `Retry-After`, problem body |
| `gateway-api.yaml` (changed) | adapter resource | `:adapters:api` | `429` response with `Retry-After` header |
| wiring | infrastructure `:bootstrap` | `bootstrap.config` | `BlockedTenants` and `AdmissionPolicy` in `DomainConfiguration`; `BlockTenantsOverLimit`, `UsageRecorder` in `AppConfiguration` |

No new ports. `BlockedTenants` is neither a repository nor an external service: it is in-memory domain state shared by the request threads and the writer, so it is a domain object exposed as a bean, like `TenantRegistry`. The clock stays `java.time.Clock`, as in item 4.

`RecordedUsageListener` keeps `app.usage` free of `app.quota`: `AdmitBusinessCall` (quota) already depends on `UsageRecorder` (usage), so injecting `BlockTenantsOverLimit` directly would create a package cycle. Alternative considered: a `java.util.function.ObjLongConsumer<UsageEvent>`; rejected because the name says nothing about what it receives.

### D-2. `BlockedTenants`: one set per period, swapped on the first block of a new period

```
record PeriodBlocks(long periodIndex, Set<Long> tenantIds)   // tenantIds = ConcurrentHashMap.newKeySet()
AtomicReference<PeriodBlocks> current = new PeriodBlocks(-1, empty)

isBlocked(id, p): blocks = current.get(); return blocks.periodIndex == p && blocks.tenantIds.contains(id)
block(id, p):     blocks = current.updateAndGet(b -> b.periodIndex < p ? new PeriodBlocks(p, newKeySet()) : b)
                  if (blocks.periodIndex == p) blocks.tenantIds.add(id)
```

- **Discard at the boundary** (spec "Blocked set is discarded at the period boundary"): a reader in period `p + 1` never sees a set of period `p` as applying, because the period index is compared on every read. The old set becomes garbage on the first block in `p + 1`. No timer, no job, no clock inside the domain.
- **Monotonic**: `block` with an older period than the held one does nothing, so a late writer call can never bring back an old set.
- Reads are one volatile read plus a `ConcurrentHashMap` lookup; writes happen only on the writer thread, but the structure is safe for any number of writers (item 8 adds `unblock` from admin threads).

Alternatives considered:
- `ConcurrentHashMap<Long, Long>` tenant → blocked period. Equally cheap, but stale entries stay until overwritten and it does not match the architecture's "set tied to one period index and replaced when the period changes".
- A scheduled task clearing the set at each boundary: adds a job and a race between the timer and requests at the boundary; rejected (D4: no reset jobs).
- Rolling over inside `isBlocked` on the request thread: not needed, since the period check already makes the old set inert, and it would add a CAS to the hot path.

### D-3. Decision on the request thread

`AdmitBusinessCall.admit(tenantId)`:

1. `Instant now = clock.instant()`; `epochSecond = now.getEpochSecond()`.
2. `policy.admit(tenantId, epochSecond)`:
   - unknown → `TenantUnknown`;
   - `blockedTenants.isBlocked(id, settings.periodIndexAt(epochSecond))` → `Blocked(tenant, settings.secondsUntilNextPeriod(epochSecond))`;
   - otherwise `Admitted(tenant)`.
3. `Admitted` → submit `UsageEvent.allowedAt(tenant, now, settings)`; `Blocked` → submit `UsageEvent.rejectedAt(tenant, now, settings)`; `TenantUnknown` → nothing.

One clock read drives the period of the decision, the `Retry-After` and the event, so they always agree.

`Retry-After` uses whole epoch seconds: `(floor(s / P) + 1) × P − s` is in `1..P` and equals the ceiling of the fractional time left, so no millisecond arithmetic is needed.

### D-4. Decision on the writer thread

`UsageRecorder.apply(event)`:

```
used = counter.record(event)        // UsageRecordingFailedException → failed++, return
recorded++
listener.usageRecorded(event, used)
```

`BlockTenantsOverLimit.usageRecorded(event, used)`:

1. Return unless `event.outcome() == ALLOWED` (rejected events never block; the tenant is already blocked or a tier change will be handled by item 8).
2. Return unless `event.periodIndex() == settings.periodIndexAt(clock.instant().getEpochSecond())` (late event of a past period, FR-6a, architecture §5).
3. Look the tenant up in `TenantRegistry`; use its **current** tier (FR-6, FR-11 groundwork). Return if absent.
4. If `settings.reachesLimit(tier, used)` → `blockedTenants.block(tenantId, event.periodIndex())`.

Blocking with the event's period (not a freshly read one) keeps step 2 and step 4 consistent even if the boundary passes between them: the `block` call then targets a period that `isBlocked` no longer asks about.

### D-5. HTTP response

`QuotaFilter` gets a third switch case:

```
case Admission.Blocked blocked -> {
    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(blocked.retryAfterSeconds()));
    problemWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, QUOTA_EXHAUSTED_DETAIL, request.getRequestURI());
}
```

`QUOTA_EXHAUSTED_DETAIL` = "Quota for the current period is exhausted". The body is the same problem shape as the `400` (type `about:blank`, title from the status, `instance` = path) with no numbers, satisfying FR-7. `chain.doFilter` is not called, so the stub never runs. The `400` behaviour is unchanged.

Alternative considered: `ResponseStatusException` thrown from a handler interceptor. Rejected: the filter already owns the guard response and keeps `/api/**` rejections in one place.

### D-6. Tests by layer

- **Domain** (`:domain`, plain JUnit): `BlockedTenantsTest` (block/isBlocked, other period not blocked, new period swaps the set, older period ignored, concurrent reads during block), `QuotaSettingsTest` (seconds until next period: middle, last second, first second), `AdmissionPolicyTest` (blocked with Retry-After, admitted, unknown), `UsageEventTest` (`rejectedAt`).
- **Application** (`:app`, fakes, `Clock.fixed` or a small mutable test clock, direct or manual executor): `BlockTenantsOverLimitTest`, `UsageRecorderTest` (listener receives `used`; not called on failure), `AdmitBusinessCallTest` (rejected event when blocked), and an overshoot test wiring `AdmitBusinessCall` + `UsageRecorder` + `BlockTenantsOverLimit` with a manual executor that holds events until released: 8 requests pass, release, next is blocked (spec "Overshoot equals the requests the writer has not applied yet").
- **Adapter** (`:adapters:api`): `QuotaFilterTest` for `429`, `Retry-After`, content type, body without numbers, chain not invoked.
- **Integration** (`:bootstrap`, Testcontainers Redis, `quota.limits.bronze=5`, a `@Primary` mutable clock in test sources): `QuotaEnforcementIntegrationTest` (block after limit with `Retry-After`, rejected counted not used, other tenant unaffected, burst consistency, rollover after advancing the clock one period, blocked again in the new period) and `QuotaEnforcementWithRedisDownTest` or a test in the same class with its own container that pauses Redis after blocking and checks `429` stays and unblocked tenants get `200`.

The mutable clock is a tiny `Clock` subclass in bootstrap test sources (`MutableClock`), since Spring caches the context per configuration and a fixed clock cannot cross a boundary.

## Risks / Trade-offs

- [Requests near a boundary are judged against a period that is a few milliseconds off from Redis keys] → Both use the same instant per request (D-3), and the writer uses the event's period for keys; residual effects are within the accepted overshoot.
- [Overshoot grows with queue backlog, e.g. under heavy load or a slow Redis] → Accepted by FR-6/NFR-5; the bounded queue caps it at `queueCapacity` events gateway-wide. Item 6 adds logging of drops and failures to make it visible.
- [After a restart, blocked tenants get extra requests until their next recorded event] → Accepted limitation (requirements §7); production direction is loading `quota:over:{p}` on startup (architecture §8).
- [A rejected event during Redis outage is lost] → Nothing is counted during an outage by design (FR-8); the tenant stays blocked from memory.
- [Integration tests depend on timing of the writer] → Use bounded polling (`Eventually`) before asserting blocking, and the deterministic overshoot test lives in `:app` with a manual executor rather than relying on timing.

## Migration Plan

No data or config migration: Redis keys and the Lua script are unchanged, and no settings are added. Clients now see `429` for over-limit tenants; the generator (item 11) already treats non-`200` responses as countable outcomes. Rollback is reverting the change.
