# Proposal

## Why

Backlog item 7, `traffic-metrics-recording` (docs/04 backlog.md). The record script counts only each tenant's `used` and `rejected`, so Redis holds nothing the snapshot (backlog item 9) and the status filter (item 10) can read cheaply: no global request rates, no period totals, and no way to know which tenants are near or over their limit without scanning every tenant. FR-12 and D8 require these to be maintained on write, in the same atomic step as the tenant's counter, so that snapshot cost stays independent of the tenant count (NFR-3).

## What Changes

- **Global per-second counts (FR-12, FR-13).** Each recorded event also increments `traffic:allowed:{s}` or `traffic:rejected:{s}` for the event's own epoch second. These keys expire after 120 s (architecture §4), enough for the snapshot's 60-second trend.
- **Global period totals (FR-12, FR-13).** Each recorded event also increments `traffic:total:allowed:{p}` or `traffic:total:rejected:{p}` for the event's own period, expiring after two periods like the tenant counters (D4).
- **Near/over set maintenance (FR-12, FR-14, D8).** After counting, the script computes the tenant's status from its `used` against the limit and near-limit boundary passed in, and fixes membership of `quota:near:{p}` and `quota:over:{p}`: over → only in `over`; near → only in `near`; ok → in neither. This happens for allowed and rejected events. The sets expire after two periods.
- **Current limit at write time (FR-6, FR-11).** The writer resolves the tenant's limit and near-limit boundary from the registry once per event, when the event is applied, and uses the same values for the script and for the blocking decision. A later tier change (backlog item 8) therefore takes effect on the tenant's next recorded event without further work.
- **Still one atomic call per event (D5, NFR-2).** All of the above runs inside the existing record script; the request path is unchanged and the call still returns `used`.
- **Redis down (FR-8, D6).** Nothing new is counted while Redis is unreachable; global counts and sets simply miss those events. No new failure handling.
- **Deviation from the current `usage-recording` spec:** its requirement "One writer applies each event with one atomic Redis call" says no key other than `used` / `rejected` is written; this change replaces that sentence. Not a deviation from docs/02.

## Capabilities

### New Capabilities

- `traffic-metrics`: global allowed/rejected counts per second and per period, and the current period's near-limit and over-limit tenant sets, maintained in Redis by the record script; their keys, expiry, the status rule against the current limit, and behaviour when Redis is unavailable. Reading them (snapshot, status filter) is added to this capability by later changes.

### Modified Capabilities

- `usage-recording`: the single atomic call per event now also writes the traffic metrics keys, and is evaluated against the tenant's current limit and near-limit boundary resolved by the writer.

## Impact

- **Gateway code:** `:domain` (limit + near-boundary value for a tier), `:app` (`UsageCounter` port signature, `UsageRecorder` resolving thresholds, `RecordedUsageListener` / `BlockTenantsOverLimit` using the same thresholds), `:adapters:infra` (`RedisUsageCounter` keys and arguments, `record-usage.lua`), `:bootstrap` (wiring).
- **Redis:** new keys `traffic:allowed:{s}`, `traffic:rejected:{s}`, `traffic:total:allowed:{p}`, `traffic:total:rejected:{p}`, `quota:near:{p}`, `quota:over:{p}` as in architecture §4. One extra round-trip-free command group per event inside the script.
- **HTTP contract:** none. No endpoint reads the new keys yet.
- **Tests:** record script / adapter Testcontainers tests, use-case unit tests, an end-to-end recording integration test.
- **Docs:** `gateway/README.md` usage recording section and Redis key list.
- **Dependencies:** none new.

## Out of scope

- Reading the metrics: `MetricsSnapshotJob`, `GET /admin/snapshot`, the status filter (backlog items 9, 10).
- The tier-change endpoint and blocked-set removal on tier change (item 8); this change only makes the writer use the limit current at write time.
- Updating near/over membership at the moment of a tier change: membership changes only on the tenant's next recorded event (docs/02 §7, accepted limitation).
- Top-N by usage or sorted sets (docs/02 §6).
- Sharding hot global keys, splitting the script for Redis Cluster, multiple gateway instances (docs/03 §8, docs/02 §6).
- Recovering counts or set membership for events lost while Redis was down.
