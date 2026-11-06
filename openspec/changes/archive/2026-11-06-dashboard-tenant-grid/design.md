# Design

## Context

Current dashboard (`/dashboard/src`, after `dashboard-polling-and-states` and `dashboard-traffic-panels`):

- `domain/snapshot.ts` models `TenantPage { rowsAvailable, rows | null, total | null, page, size }` and `TenantRow { id, name, tier, limit, used | null, rejected | null, status | null }`.
- `useDashboard()` returns `DashboardView` with `snapshot`, `stale`, `tierControlsEnabled` (true only while `live`) and the header fields; the Tenants region in `DataRegions` is an empty `<section>`.
- Ports (`application/ports.ts`): `SnapshotSource`, `HealthSource`, `Scheduler`, carried by `DashboardPortsContext`; `startPolling` gives start-now, interval, timeout and abort; `test/support/` has `ManualScheduler`, fake sources with `answerLatest` / `failLatest`, and snapshot fixtures.
- Gateway contract used (unchanged, `gateway-api.yaml`, `tier-administration` spec): `GET /admin/tiers` → `200 [{name, limit}]` in Bronze, Silver, Gold order; `PUT /admin/tenants/{id}/tier` with `{"tier": name}` → `200 {id, name, tier, limit}`, `400`/`404` `application/problem+json`. Through nginx a stopped gateway answers `502`/`504`.
- Lint forbids adapters/UI imports in application and `fetch` outside adapters, so the application can't recognise adapter error classes.

Requirements: `specs/dashboard-tenant-grid`, modified `specs/dashboard-states`. Motivation: proposal.md.

## Goals / Non-Goals

**Goals:**
- One pure function turns the snapshot page, the per-row change states, the tier list and the enabled flag into a `TenantGridView`; components only render it and report selections.
- Per-row tier change state is a pure reducer, so saving / confirmed / failed / revert are unit-tested without React or timers.
- Every failure mode of a change (status, network, timeout) is classified once, in the application layer, into a domain failure that the UI turns into text.

**Non-Goals:**
- No snapshot query parameters or page navigation (item 15); the grid shows whatever page the snapshot returns.
- No optimistic usage recalculation beyond using the new limit for the percentage; status stays what the snapshot reports until it catches up.
- No retry of a failed change; the operator chooses again.

## Decisions

### D-1. Module map by layer

| Layer | Module (under `dashboard/src/`) | Contents | Depends on |
|---|---|---|---|
| domain | `domain/tiers.ts` | `TierOption { name, limit }`; `ChangedTier { id, name, tier, limit }` | — |
| domain | `domain/tenantUsage.ts` | `usagePercent(used, limit)` = `floor(used × 100 / limit)`, `null` when `used` is `null` or `limit ≤ 0` | — |
| domain | `domain/tierChange.ts` | `TierChangeFailure` union (`notFound`, `rejected`, `serverError {status}`, `timedOut`, `unreachable`); `TierChangeRefused` error carrying an HTTP `status` (thrown by the adapter, recognised by the application); `RowChange` union `saving {requested}` / `confirmed {tier, limit}` / `failed {failure}`; `TierChanges` = `ReadonlyMap<tenantId, RowChange>`; reducer `nextTierChanges(changes, event)` per D-3 | `tiers.ts`, `snapshot.ts` |
| domain | `domain/tenantGrid.ts` | `TenantGridView` (`kind: 'rows' \| 'unavailable'`, rows, `shownCount`, `total`, `tierOptions`); `GridRow` (id, name, displayed tier and limit, used, rejected, status, `usagePercent`, `saving`, `failure`, `selectorEnabled`); `tenantGridOf(page, changes, tiers, controlsEnabled)` | above, `snapshot.ts` |
| application | `application/ports.ts` (changed) | `TierAdministration { fetchTiers(signal): Promise<TierOption[]>; changeTier(tenantId, tier, signal): Promise<ChangedTier> }` | domain |
| application | `application/dashboardPortsContext.ts` (changed) | `DashboardPorts` gains `tierAdministration` | `ports.ts` |
| application | `application/requestWithTimeout.ts` | `requestWithTimeout(task, timeoutMs, scheduler, signal?)`: runs `task(signal)` with an `AbortController`, aborts and rejects with `RequestTimedOut` after `timeoutMs`; also aborts when the outer signal aborts | `ports.ts` |
| application | `application/tierChangeFailure.ts` | `tierChangeFailureOf(error)`: `RequestTimedOut` → `timedOut`; `TierChangeRefused` 404 → `notFound`, 400 → `rejected`, other status → `serverError`; anything else → `unreachable` | domain, `requestWithTimeout.ts` |
| application | `application/useTiers.ts` | loads tiers with `startPolling` (interval 5 s, timeout 2 s) and stops the loop after the first success; returns `TierOption[] \| null` | `polling.ts`, ports |
| application | `application/useTenantGrid.ts` | `useReducer(nextTierChanges)`; dispatches `rowsReceived` whenever the shown snapshot changes; `changeTier(row, tier)` dispatches `requested`, runs `requestWithTimeout(changeTier, 2 s)` and dispatches `succeeded` / `failed`; aborts in-flight changes on unmount; returns `{ grid: tenantGridOf(...), changeTier }` | all above |
| adapter (out, HTTP) | `adapters/api/httpTierAdministration.ts` | `createHttpTierAdministration(fetchFn)`: `GET /admin/tiers` (non-2xx or invalid body → reject); `PUT /admin/tenants/{id}/tier` with `Content-Type: application/json`, body `{"tier": name}`; non-2xx → `TierChangeRefused(status)`; `200` body validated into `ChangedTier` | `application/ports`, domain, `json.ts` |
| UI | `ui/format.ts` (changed) | `formatUsage(used, limit, percent)` → `85 / 100 (85%)` or `—`; status labels OK / Near / Over / `—`; `tierOptionLabel` → `Silver (1,000)`; `tierChangeFailureText(failure)` per D-4 | domain |
| UI | `ui/components/TenantGrid.tsx` | table with caption-less headers, "No tenants", "Tenant rows unavailable", footer "Showing N of T tenants" | `format.ts` |
| UI | `ui/components/TenantGridRow.tsx`, `ui/components/TierSelector.tsx` | one row; a native `<select aria-label="Tier for {name}">`, "Saving…" marker, inline error with `role="alert"` scoped to the row | `format.ts` |
| UI | `ui/components/DataRegions.tsx`, `DashboardScreen.tsx`, `ui/App.tsx` (changed) | `App` calls `useDashboard()` and `useTenantGrid(view)`; `DashboardScreen` passes `grid` and `onTierChange` to `DataRegions`, which renders `TenantGrid` in the Tenants region | above |
| composition root | `main.tsx` (changed) | adds `tierAdministration: createHttpTierAdministration(boundFetch)` | everything |

`TierChangeRefused` sits in the domain so that both the adapter (which throws it) and the application (which classifies it) can depend on it without breaking the inward-only rule. `test/support/` gains `FakeTierAdministration` (deferred answers like the fake sources) and tier fixtures.

*Alternative:* return a result union from the port instead of throwing — rejected: timeouts and aborts arrive as rejections anyway, so classification in one place on the rejection path is simpler than mixing both styles.

### D-2. Tier list loading reuses `startPolling`

`useTiers` runs `startPolling({ intervalMs: 5000, timeoutMs: 2000 })` and calls the returned `stop` from `onSuccess`. `startPolling` checks `stopped` before scheduling the next run, so exactly one successful request is sent and failures retry every 5 s (spec "Gateway down at start"). Until it succeeds, `tenantGridOf` offers only the row's own tier and disables the selector (spec "Tiers not loaded yet").

*Alternative:* hard-code Bronze/Silver/Gold — rejected: limits are configuration (NFR-8) and the labels show them.

### D-3. Per-row change reducer

```
requested {id, tier}          → changes[id] = saving{requested: tier}         (any previous failed/confirmed replaced)
succeeded {id, tier, limit}   → if saving: changes[id] = confirmed{tier, limit}
failed {id, failure}          → if saving: changes[id] = failed{failure}
rowsReceived {rows}           → drop confirmed{tier} entries whose row now reports that tier
```

Displayed tier per row: `saving.requested`, else `confirmed.tier`, else the row's `tier`. Displayed limit: `confirmed.limit` while confirmed, else the row's `limit`. A `failed` entry shows the snapshot tier (the revert) plus the error until the next `requested` for that row. `succeeded` / `failed` arriving for a row that is no longer `saving` (e.g. after unmount races) are ignored.

"Snapshot catches up" is matched by tier value, not by poll timing: a snapshot requested before the `PUT` and received after it still shows the old tier and leaves `confirmed` in place (spec "Older snapshot after success"). A `confirmed` entry for a tenant that leaves the page stays harmlessly until the tenant reappears with that tier.

*Alternative:* clear `confirmed` on the first snapshot received after success — rejected: the in-flight snapshot race makes the selector flick back to the old tier for one second.

### D-4. Failure texts

| Failure | Text after "Tier change failed: " |
|---|---|
| `notFound` | tenant not found |
| `rejected` | tier rejected by the gateway |
| `serverError {status}` | gateway error ({status}) |
| `timedOut` | no answer within 2 s |
| `unreachable` | gateway unreachable |

The texts live in `ui/format.ts`; the domain only carries the failure kind.

### D-5. Timeout of 2 s

The tier change uses the same 2 s timeout as the polls (FR-21 lists "times out" without a value). On timeout the `PUT` is aborted; the gateway may still have applied it, in which case the next snapshot shows the new tier and the grid follows it. Accepted for the prototype and noted in risks.

## Risks / Trade-offs

- [A timed-out change may have been applied by the gateway] → the error says "no answer", and the next snapshot shows the real tier; the selector always reverts to the latest snapshot's tier, never to a guess.
- [`confirmed` is kept until a snapshot reports the tier; if another client changes the tier back first, the row keeps showing the operator's tier] → single-operator prototype (docs/02 §7); the next change by this operator or a reload resolves it.
- [Status after an upgrade stays "Over" until the tenant's next request] → known and documented in docs/02 §7; the grid shows what the snapshot reports, with the percentage against the new limit.
- [Tier list fetched once, so a limit changed by restarting the gateway with new configuration is not picked up until reload] → limits are static configuration (FR-10); a reload picks them up.

## Migration Plan

Dashboard-only, no contract change. Rolled out by rebuilding the dashboard image; rollback is the previous image. Manual S6 (change a tier and watch the tenant unblock), S7 (change a tier with Redis stopped) and S8 checks against the running stack are left to the user.
