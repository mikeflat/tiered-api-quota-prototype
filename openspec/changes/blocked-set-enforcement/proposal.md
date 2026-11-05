# Proposal

## Why

Backlog item 5 (`blocked-set-enforcement`, Phase 2). The gateway now records every allowed request in Redis (item 4), but nothing is ever rejected: a tenant can call without limit. This change turns the recorded counts into enforcement, keeping the quota decision in memory with no I/O, as the whole design depends on (FR-4, NFR-2, D5).

## What Changes

- Add a per-period, in-memory **blocked set** of tenants known to be over their limit. It belongs to one period index; when the period changes, the set is discarded and every tenant starts the new period unblocked (FR-6a, S3). No reset job: the switch happens on the first access in the new period.
- The **writer** uses the `used` value returned by the record script: after an allowed event, if `used ≥` the tenant's current limit (its tier's limit at processing time) and the event's period is still the current period, it adds the tenant to the blocked set (FR-6, D7).
- The **quota decision** on the request thread checks the blocked set for the current period: not blocked → served with `200` as today; blocked → **`429 Too Many Requests`** with `Retry-After` (whole seconds until the next period boundary, minimum 1) and a minimal `application/problem+json` body with no usage data (FR-5, FR-7, S2).
- A blocked request produces a **rejected** usage event, so `quota:rejected:{tenantId}:{p}` grows and `used` does not (FR-5, FR-6). Until now every event was allowed.
- Overshoot is accepted and bounded by requests that pass between crossing the limit and the writer seeing the Redis response (FR-6, NFR-5). Testcontainers tests show blocking after the limit, bounded overshoot and period rollover (NFR-9).
- Already blocked tenants stay blocked when Redis becomes unreachable, since the blocked set is in memory and checked without Redis (falls out of the design; FR-8, D6). One integration test shows this; logging and health stay with item 6.
- The OpenAPI contract for `GET /api/v1/resource` gains the `429` response with the `Retry-After` header.

No new configuration: the existing period length and tier limits drive enforcement.

## Capabilities

### New Capabilities
- `quota-enforcement`: the per-period blocked set, how tenants enter it (writer comparing `used` with the current limit for the current period), the in-memory quota decision, the `429` response with `Retry-After`, rollover at the period boundary, bounded overshoot, and enforcement from memory while Redis is unreachable.

### Modified Capabilities
- `usage-recording`: business requests now produce a **rejected** event when the tenant is blocked (the spec said every event is allowed until enforcement exists). The writer's use of the returned `used` is specified in `quota-enforcement`; the record call itself is unchanged.

## Impact

- **Gateway `:domain`**: new blocked-set type and decision outcome for blocked tenants; `QuotaSettings` gains seconds-to-next-boundary; `UsageEvent` gains a rejected factory; `AdmissionPolicy` consults the blocked set.
- **Gateway `:app`**: `AdmitBusinessCall` submits allowed or rejected events; a new use case run by the writer decides whether to block after each recorded allowed event; `UsageRecorder` calls it.
- **Gateway `:adapters:api`**: `QuotaFilter` answers `429` with `Retry-After` and a problem body; OpenAPI adds the `429` response.
- **Gateway `:bootstrap`**: wiring of the blocked set and the new use case; new Testcontainers integration tests.
- **Redis**: no key or script changes. The record script already returns `used` for both outcomes.
- **Dashboard, generator, docker compose, scripts**: unchanged.

## Out of scope

- Logging Redis failures and drops, Redis status in `/actuator/health`, liveness/readiness groups (item 6, `redis-failure-and-health`).
- Global traffic counters and near/over sets, passing the limit into the Lua script (item 7).
- Removing a tenant from the blocked set on a tier change, and the admin API (item 8, `admin-tenants-and-tiers`). This change only reads the current tier when deciding to block.
- Loading `quota:over:{p}` at startup to restore the blocked set after a restart: accepted limitation (requirements §7).
- Multiple gateway instances, usage or remaining-quota headers, and other items in requirements §6.
