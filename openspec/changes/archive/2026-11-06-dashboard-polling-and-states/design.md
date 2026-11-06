# Design

## Context

`/dashboard` is the skeleton from `bootstrap-skeleton-dashboard`: `src/ui/App.tsx` renders the heading and "No data yet.", `src/domain/`, `src/application/`, `src/adapters/api/` and `src/ui/components/` hold only `.gitkeep`, and `.oxlintrc.json` enforces the layering (domain: no React, no `fetch`, no `window`/`setTimeout`/`setInterval`; application: no adapters/UI, no `fetch`; UI: no adapters, no `fetch`; adapters: no React/UI). Vitest + React Testing Library run in jsdom; no state or UI library is installed.

The gateway contract consumed here (unchanged by this change):
- `GET /admin/snapshot` → `200` `{ period: {index, endsAt, secondsLeft}, metrics: {perSecond[60], totals, tenantsNear, tenantsOver} | null, tenants: {rowsAvailable, rows | null, total | null, page, size} }` (`gateway-api.yaml`, `admin-snapshot` spec).
- `GET /actuator/health` → `200` `{"status":"UP","components":{"redis":{"status":"UP"}}}` or `503` with `DOWN` for both while Redis is unreachable (`show-components: always`, `show-details: never`).
- Through nginx a stopped gateway answers `502`/`504` after at most 2 s connect timeout; in `npm run dev` the Vite proxy answers `5xx` or the request errors.

Requirements: `specs/dashboard-polling`, `specs/dashboard-states`, the modified `specs/dashboard-runtime`. Motivation and scope: proposal.md.

## Goals / Non-Goals

**Goals:**
- One polling mechanism (start immediately, interval measured from start, no overlap, 2 s timeout, cancel on unmount) used for both the snapshot and health, testable without React and without real timers.
- One pure state machine and one pure view derivation that later items (13–15) read through a single hook, so header, panels and grid never re-derive connection logic.
- Every FR-21 state of this item covered by an RTL test that drives the real `App` through fake ports.

**Non-Goals:**
- No snapshot query parameters yet: `dashboard-tenant-filters` extends the snapshot port with a query.
- No rendering of snapshot numbers: the Traffic, Period totals and Tenants regions are empty frames (heading plus state markers) that items 13 and 14 fill.
- No retry back-off: the cadence stays 1 s while unreachable, as FR-21 says "polling continues".

## Decisions

### D-1. Module map by layer

| Layer | Module (under `dashboard/src/`) | Contents | Depends on |
|---|---|---|---|
| domain | `domain/snapshot.ts` | Types `AdminSnapshot`, `QuotaPeriod`, `TrafficMetrics`, `SecondTraffic`, `PeriodTotals`, `TenantPage`, `TenantRow`, `TenantStatus` mirroring the OpenAPI schema (`metrics`, `rows`, `total`, `used`, `rejected`, `status` nullable) | — |
| domain | `domain/enforcement.ts` | `EnforcementStatus = 'ok' \| 'degraded' \| 'unknown'`; `HealthReport { redisStatus: string \| null }`; `enforcementStatusOf(report)` (`DOWN` → degraded, `UP` → ok, else unknown) | — |
| domain | `domain/connection.ts` | `ConnectionState` union and `nextConnectionState(state, event)` reducer; events `snapshotReceived {snapshot, at}`, `snapshotFailed {at}`; constant `failuresBeforeUnreachable = 2` | `snapshot.ts` |
| domain | `domain/dashboardView.ts` | `DashboardView` and `dashboardViewOf(connection, enforcement, now)`; constant `reconnectedNoticeMillis = 3000` | `connection.ts`, `enforcement.ts` |
| domain | `domain/pollDelay.ts` | `nextPollDelay(intervalMs, startedAt, finishedAt)` = `max(0, interval − elapsed)` | — |
| application | `application/ports.ts` | `SnapshotSource.fetchSnapshot(signal): Promise<AdminSnapshot>`; `HealthSource.fetchHealth(signal): Promise<HealthReport>`; `Scheduler { now(): number; schedule(fn, ms): Cancel }` | domain |
| application | `application/polling.ts` | `startPolling({ task, intervalMs, timeoutMs, scheduler, onSuccess, onFailure }): Stop` — framework-free loop | `ports.ts`, `pollDelay.ts` |
| application | `application/DashboardPorts.tsx` | `DashboardPortsContext` + `DashboardPortsProvider` carrying the three ports | `ports.ts`, `react` |
| application | `application/usePolling.ts` | thin hook: `startPolling` in `useEffect`, `stop` in cleanup | `polling.ts`, `react` |
| application | `application/useDashboard.ts` | snapshot poll (1 s) → `useReducer(nextConnectionState)`; health poll (5 s) → enforcement state; 1 s `now` tick; returns `dashboardViewOf(...)` | all above |
| adapter (out, HTTP) | `adapters/api/httpSnapshotSource.ts` | `createHttpSnapshotSource(fetchFn)`: `GET /admin/snapshot`, `Accept: application/json`, non-`2xx` → `SnapshotRequestFailed`; body mapped and validated by `toAdminSnapshot(json)` (throws on missing/ill-typed `period` or `tenants`) | `application/ports`, domain |
| adapter (out, HTTP) | `adapters/api/httpHealthSource.ts` | `createHttpHealthSource(fetchFn)`: `GET /actuator/health`; reads the JSON body for `200` **and** `503`; returns `{ redisStatus }` from `components.redis.status`; throws on network error, other statuses or a non-JSON body | `application/ports`, domain |
| adapter (out, time) | `adapters/time/browserScheduler.ts` | `Scheduler` over `Date.now`, `globalThis.setTimeout/clearTimeout` | `application/ports` |
| UI | `ui/App.tsx` | calls `useDashboard()`, renders `DashboardScreen` | application |
| UI | `ui/components/DashboardScreen.tsx` | composes banners, loading indicator, stale note and data regions from a `DashboardView` prop | domain types |
| UI | `ui/components/GatewayUnreachableBanner.tsx`, `DegradedEnforcementBanner.tsx`, `ReconnectedNotice.tsx`, `LoadingIndicator.tsx`, `LastUpdatedNote.tsx`, `DataRegions.tsx`, `MetricsUnavailable.tsx` | small presentational components | — |
| composition root | `main.tsx` | creates `createHttpSnapshotSource(fetch)`, `createHttpHealthSource(fetch)`, `browserScheduler`; wraps `<App/>` in `DashboardPortsProvider` | everything |

The adapters take `fetch` as a parameter (default wired in `main.tsx`) so their tests stub it without touching globals. `adapters/time/` is a new folder under the existing `src/adapters/**` lint override, so no lint change is needed; the README layer table gets a row for it. The `.gitkeep` files of `domain/`, `application/`, `adapters/api/` and `ui/components/` are deleted.

*Alternative:* put `Scheduler` behind `vi.useFakeTimers()` instead of a port — rejected: the project context makes clocks and timers ports, and domain lint already forbids `setTimeout`; an injected scheduler also makes the polling loop testable without jsdom.

### D-2. Polling loop (`startPolling`)

```
run():  startedAt = now(); controller = new AbortController()
        cancelTimeout = schedule(() => controller.abort(), timeoutMs)
        task(controller.signal)
          .then(onSuccess, onFailure)       // only if not stopped and not already settled
          .finally(() => { cancelTimeout(); if !stopped: cancelNext = schedule(run, nextPollDelay(intervalMs, startedAt, now())) })
stop(): stopped = true; controller.abort(); cancelTimeout(); cancelNext()
```

- The first `run()` is synchronous on start (spec: first poll without waiting).
- Exactly one request per kind is in flight because the next `run` is only scheduled from `finally` (spec: no overlap).
- A timed-out request is aborted, which rejects the `fetch`; a `settled` flag makes sure the outcome is reported once, as a failure (spec: 2 s timeout).
- After `stop()`, outcomes are ignored, so no React state update follows an unmount. React StrictMode's double effect run in development is covered by the same `stop()`.

`AbortController`/`AbortSignal` are web-standard objects, not I/O, so they are allowed in the application layer.

*Alternative:* `setInterval` with an "in flight" guard — rejected: skipped ticks make the cadence after a slow poll irregular and still need a timeout timer; a self-scheduling `setTimeout` chain is simpler to reason about and to test.

### D-3. Connection state machine (domain)

```
loading{failures}  --received-->  live{snapshot, receivedAt, failures:0, reconnectedAt:null}
loading{failures}  --failed-->    failures+1 < 2 ? loading{failures+1} : unreachable{last:null}
live{..}           --received-->  live{new snapshot, receivedAt, failures:0, reconnectedAt kept}
live{..}           --failed-->    failures+1 < 2 ? live{.., failures+1} : unreachable{last:{snapshot, receivedAt}}
unreachable{last}  --received-->  live{snapshot, receivedAt, failures:0, reconnectedAt:at}
unreachable{last}  --failed-->    unreachable{last}
```

`dashboardViewOf(connection, enforcement, now)` returns:

| Field | Value |
|---|---|
| `loading` | no snapshot received yet (state `loading`, or `unreachable` with `last: null`) |
| `gatewayUnreachable` | state is `unreachable` |
| `snapshot` | `live.snapshot`, or `unreachable.last.snapshot`, else `null` |
| `stale` | unreachable with a last snapshot |
| `lastUpdatedSecondsAgo` | `floor((now − receivedAt) / 1000)` when stale, else `null` |
| `reconnectedNoticeVisible` | `live` and `reconnectedAt` set and `now − reconnectedAt < 3000` |
| `enforcementDegraded` | `enforcement === 'degraded'` |
| `metricsAvailable` | snapshot present and `snapshot.metrics !== null` |
| `tierControlsEnabled` | state is `live` |

Keeping the notice as a timestamp (instead of an "expired" event and its own timer) keeps the reducer free of time-based events; the 1 s `now` tick that already drives "last updated N s ago" also hides the notice, so it shows for 3–4 s ("about 3 s" in the spec).

*Alternative:* a `useState` per flag in the hook — rejected: transitions like "unreachable only after 2 failures" and "reconnected only after unreachable" would be spread over effects and could not be unit-tested without React.

### D-4. Health interpretation

The adapter reports only `components.redis.status` (or `null` when absent); the domain maps it. A `503` with a JSON body is a valid health answer (Redis down), not a failure; a `502`/`504` from nginx or the dev proxy has no health JSON and throws, so `useDashboard` sets `unknown`. Health failures never count towards "unreachable" — FR-21 ties that only to snapshot polls, and the gateway's overall `DOWN` is caused by Redis, not by the gateway itself.

### D-5. Screen structure and styling

```
<header> h1 "Quota dashboard" </header>
<div class="banners">  GatewayUnreachableBanner (role=alert, red)
                       DegradedEnforcementBanner (role=alert, yellow)
                       ReconnectedNotice (role=status, green) </div>
<main>
  loading  → LoadingIndicator (role=status, "Loading dashboard…")
  else     → LastUpdatedNote ("last updated N s ago", only when stale)
             <div class="data-regions" data-stale=…>
               <section aria-labelledby> h2 Traffic        → MetricsUnavailable | (filled by item 13)
               <section aria-labelledby> h2 Period totals  → MetricsUnavailable | (filled by item 13)
               <section aria-labelledby> h2 Tenants        → (filled by item 14)
</main>
```

Greying is a CSS class on the regions wrapper (`.is-stale { opacity: .5; filter: grayscale(1) }`) plus `aria-describedby` pointing at the "last updated" note, so tests and assistive tech can see it. Banner colours are added to `index.css` as variables for light and dark schemes; no component library (FR-22). The loading text contains no digits, keeping the "no zeros pretending to be data" check.

### D-6. Tests

Tests live in `dashboard/test/`, never next to the source: `test/` mirrors `src/` folder by folder (`src/domain/connection.ts` → `test/domain/connection.test.ts`), shared doubles and fixtures sit in `test/support/`, and `test/setup.ts` is the Vitest setup. Vitest collects only `test/**/*.test.{ts,tsx}`; a `tsconfig.test.json` referenced from `tsconfig.json` type-checks the tree in `npm run build`; each `src/<layer>/**` lint override also lists `test/<layer>/**`, so tests obey the same layering rules.

| Layer | Test file | Technique |
|---|---|---|
| domain | `connection.test.ts`, `dashboardView.test.ts`, `enforcement.test.ts`, `pollDelay.test.ts` | plain Vitest, table-style cases for each transition and view field |
| application | `polling.test.ts` | `ManualScheduler` test double (`test/support/manualScheduler.ts`: virtual `now`, `advanceBy(ms)` running due callbacks) and controllable promises; covers immediate first poll, cadence, no overlap, timeout → failure once, stop cancels |
| application | `useDashboard.test.tsx` | `renderHook` with fake ports and `ManualScheduler` |
| adapter | `httpSnapshotSource.test.ts`, `httpHealthSource.test.ts` | stubbed `fetch` returning real `Response` objects: `200` valid, `200` `metrics:null`, `200` malformed / non-JSON, `502`, network `TypeError`, aborted signal; health `200` UP, `503` DOWN, `502` HTML |
| adapter | `browserScheduler.test.ts` | `vi.useFakeTimers()` — the one place that touches real timer APIs |
| UI | `App.test.tsx` (rewritten) and `DashboardScreen.test.tsx` | RTL: the real `App` inside `DashboardPortsProvider` with fake sources and `ManualScheduler`, one test per `dashboard-states` scenario; presentational cases with a `DashboardView` prop |

Test names follow the snake_case rule (e.g. `two_failed_snapshot_polls_show_gateway_unreachable`).

### D-7. Files

- Added: the modules in D-1; their tests under `test/` mirroring `src/`; shared doubles and fixtures in `test/support/` (`manualScheduler.ts`, `fakeSources.ts`, `snapshots.ts`, `deferred.ts`).
- Changed: `src/main.tsx` (wiring; its leftover comment line is removed per the no-comments rule), `src/ui/App.tsx`, `src/ui/index.css`, `src/ui/App.test.tsx` (moved to `test/ui/App.test.tsx`), `src/test/setup.ts` (moved to `test/setup.ts`), `vite.config.ts`, `tsconfig.json` + new `tsconfig.test.json`, `.oxlintrc.json` (test tree layout, see D-6), `dashboard/README.md` (layer table row for `adapters/time/`, short "Live data and states" section).
- Removed: `.gitkeep` in `domain/`, `application/`, `adapters/api/`, `ui/components/`.

## Risks / Trade-offs

- [Async promise resolution inside fake-time tests can make RTL tests order-sensitive] → `ManualScheduler.advanceBy` is wrapped in `await act(async …)` and fake sources resolve deferred promises explicitly; no real timers in UI tests.
- [The 1 s tick re-renders the whole screen every second] → cheap for a few small components; panels added later are still bounded by one page of 10 rows. Memoisation can be added in item 13 if needed.
- [Treating `4xx` and malformed bodies as failures deviates from the FR-21 trigger list] → flagged in the `dashboard-polling` spec; such answers only come from a dashboard/proxy bug and showing "unreachable" is safer than showing wrong data.
- [nginx `proxy_read_timeout` is 5 s while the dashboard aborts at 2 s] → the browser abort closes the connection; nginx logs a `499`, which is harmless.
- [The reconnected notice lasts 3–4 s, not exactly 3 s] → acceptable for "brief"; a dedicated timer can replace the tick if exactness is ever needed.
- [Stale-data checks need a running stack to observe end to end] → covered by RTL tests with fake ports; the live check (stop gateway/Redis) is left to the user per the test execution rule.

## Migration Plan

None: the dashboard has no consumers. Rollback is reverting the change; the gateway is untouched.
