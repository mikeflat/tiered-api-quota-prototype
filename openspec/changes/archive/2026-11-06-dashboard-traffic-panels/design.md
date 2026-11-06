# Design

## Context

`dashboard-polling-and-states` left this structure in `/dashboard/src` (see its archived design):

- `domain/snapshot.ts` already models every field this change shows: `QuotaPeriod.secondsLeft`, `TrafficMetrics.perSecond[]` (`second`, `allowed`, `rejected`, oldest first), `totals`, `tenantsNear`, `tenantsOver`; `metrics` is `null` when Redis can't be read.
- `domain/connection.ts` holds `ConnectionState` (`loading` / `live` / `unreachable`), each shown snapshot paired with `receivedAt`.
- `domain/dashboardView.ts` derives `DashboardView` from connection, `EnforcementStatus` and `now`; `useDashboard` already supplies `now` from a 1 s tick of the `Scheduler` port and the enforcement status from the 5 s health poll.
- `ui/components/DashboardScreen.tsx` renders the title header, banners and, once a snapshot exists, `DataRegions` with empty Traffic / Period totals / Tenants regions and the "Metrics unavailable" marker.
- `.oxlintrc.json` forbids React, `fetch` and timers in domain, adapters/UI in application, adapters in UI.

So all data is already in the view; this change derives a few more values and draws them. No adapter, port or gateway change. Requirements: `specs/dashboard-header`, `specs/dashboard-traffic`.

## Goals / Non-Goals

**Goals:**
- All arithmetic (countdown, current second, peaks, sparkline coordinates) in pure domain functions with plain unit tests; components only format and lay out.
- The countdown works without Redis and never shows a value that isn't backed by a live snapshot.
- The trend is one small hand-written SVG that renders identically in jsdom and the browser, so RTL tests can assert on it.

**Non-Goals:**
- No new polls, no change to poll cadence or the connection state machine.
- No axes, gridlines, tooltips, hover or animation on the trend; no time labels per point.
- No change to the Tenants region (item 14).

## Decisions

### D-1. Module map by layer

| Layer | Module (under `dashboard/src/`) | Contents | Depends on |
|---|---|---|---|
| domain | `domain/resetCountdown.ts` | `secondsUntilReset(period, receivedAt, now)` = `max(0, period.secondsLeft − floor((now − receivedAt) / 1000))` | `snapshot.ts` |
| domain | `domain/trafficSummary.ts` | `currentSecondOf(perSecond): SecondTraffic \| null` (newest entry or `null` when empty); `trafficPeaksOf(perSecond): { allowed, rejected }` (max of each, `0` when empty) | `snapshot.ts` |
| domain | `domain/trendGeometry.ts` | `trendSlots = 60`; `trendLinesOf(perSecond, { width, height }): { allowed: TrendPoint[], rejected: TrendPoint[] }` per D-3 | `snapshot.ts`, `trafficSummary.ts` |
| domain | `domain/dashboardView.ts` (changed) | `DashboardView` gains `connectionStatus: 'connecting' \| 'connected' \| 'unreachable'`, `enforcement: EnforcementStatus` and `secondsUntilReset: number \| null`; existing fields unchanged | + `resetCountdown.ts` |
| application | — | unchanged: `useDashboard` already passes connection, enforcement and `now` to `dashboardViewOf` | — |
| UI | `ui/format.ts` | `formatCount(n)` (`Intl.NumberFormat('en-US')`, e.g. `12,045`); `formatCountdown(seconds \| null)` (`—`, `m:ss`, `h:mm:ss`); labels for connection and enforcement statuses | domain types |
| UI | `ui/components/HeaderStatus.tsx` | `<dl>` with "Gateway", "Enforcement", "Resets in" and their values; a status modifier class per value for colour | `format.ts` |
| UI | `ui/components/TrafficPanel.tsx` | current allowed / rejected req/s from `currentSecondOf`, then `TrafficTrend` | `trafficSummary.ts`, `format.ts` |
| UI | `ui/components/TrafficTrend.tsx` | `<figure>` with an `<svg role="img" aria-label=…>` of two `<polyline>`s from `trendLinesOf`, and a text legend | `trendGeometry.ts`, `trafficSummary.ts` |
| UI | `ui/components/PeriodTotalsPanel.tsx` | `<dl>` with "Allowed", "Rejected", "Near limit", "Over limit" | `format.ts` |
| UI | `ui/components/DataRegions.tsx` (changed) | takes `metrics: TrafficMetrics \| null` instead of `metricsAvailable`; renders `TrafficPanel` / `PeriodTotalsPanel` or `MetricsUnavailable` | above |
| UI | `ui/components/DashboardScreen.tsx` (changed) | renders `HeaderStatus` in `<header>` next to the title in every state, including loading; passes `view.snapshot?.metrics ?? null` to `DataRegions` | above |
| UI | `ui/index.css` (changed) | header status layout, `--allowed` / `--rejected` line colours with dark variants, panel number styles | — |

Number and countdown formatting sit in the UI layer because they are presentation; they are still pure functions, tested in `test/ui/format.test.ts`. `metricsAvailable` stays in `DashboardView` (used by later items), but `DataRegions` reads the metrics object directly so it can never render numbers from a `null` metrics value.

*Alternative:* a `useTrafficPanels` application hook — rejected: there is no new I/O or state, only derivations of the existing view, so a hook would be an empty pass-through.

### D-2. Countdown from `secondsLeft`, not `endsAt`

`secondsUntilReset` uses the gateway's `secondsLeft` and the browser's elapsed time since that snapshot arrived (both `now` and `receivedAt` come from the same `Scheduler`). It is `null` unless the connection is `live`, which gives "—" while loading and while unreachable (spec "Countdown is not shown without live data"). Between polls it ticks with the existing 1 s `now`; each new snapshot re-anchors it, so a period boundary simply shows the new `secondsLeft` (S3).

*Alternative:* `endsAt − Date.now()` — rejected: it depends on the browser clock agreeing with the gateway clock; a skewed laptop clock would show a wrong countdown. `secondsLeft` is measured on the gateway, and only the sub-second poll delay adds error.

*Alternative:* keep counting down from the last snapshot while unreachable — rejected: after the boundary it would have to guess the period length, which the snapshot doesn't carry, and a confident countdown contradicts the "Gateway unreachable" banner.

### D-3. Sparkline geometry

```
slots      = 60                       (trendSlots)
entries    = last `slots` entries of perSecond, oldest first
offset     = slots − entries.length   (fewer entries are right-aligned: newest is always at the right edge)
x(i)       = (offset + i) × width / (slots − 1)
scaleMax   = max(peak allowed, peak rejected, 1)
y(v)       = height − v / scaleMax × height
```

Both lines share `scaleMax` (spec "Shared scale"); the `1` floor keeps an all-zero minute flat on the bottom instead of dividing by zero. The SVG uses `viewBox="0 0 600 120"`, `preserveAspectRatio="none"` and `vector-effect="non-scaling-stroke"` so it stretches to the panel width while lines stay 2 px. Coordinates are rounded to 0.1 so the `points` attribute is stable in tests.

The accessible name is built from `trafficPeaksOf`: "Requests per second over the last 60 seconds, peak 200 allowed and 50 rejected". The legend is plain text with a colour swatch, so the lines are identifiable without colour (FR-22).

*Alternative:* one `<path>` with an area fill, or a separate scale per line — rejected: two polylines are the simplest marks that compare directly, and separate scales would make a small rejected rate look as large as the allowed rate.

### D-4. Status labels and colours

| View value | Header text | Class |
|---|---|---|
| `connecting` / `connected` / `unreachable` | Connecting / Connected / Unreachable | `status-neutral` / `status-ok` / `status-error` |
| enforcement `unknown` / `ok` / `degraded` | Unknown / OK / Degraded | `status-neutral` / `status-ok` / `status-warning` |

Colours reuse the existing `--success-*`, `--error-*`, `--warning-*` variables. The header is outside `<main>` and outside the greyed `data-regions`, so statuses stay readable while data is stale, and the existing "no digits in main while loading" test still holds (the countdown shows "—" anyway).

## Risks / Trade-offs

- [Countdown can lag by up to one poll (~1 s) after a slow poll] → it re-anchors on every snapshot and is floored at 0; a 1 s error on a 60 s period is acceptable for an operator view (NFR-6 allows ~2 s).
- [`now` and the snapshot tick are independent 1 s timers, so the countdown may occasionally skip or repeat a second] → accepted; the value is always derived, never accumulated, so it can't drift.
- [`Intl.NumberFormat('en-US')` fixes the separator regardless of browser locale] → deliberate for predictable tests and a consistent prototype screen; locale-aware formatting is out of scope.
- [A trend with only a few seconds of history right after Redis returns] → right-aligned points make that visible rather than stretching three seconds across the full width.

## Migration Plan

Dashboard-only, no contract change. Rolled out by rebuilding the dashboard image with the stack scripts; rollback is the previous image. Manual checks against a running stack (S3 countdown restart, S7 with Redis stopped, S8 with the gateway stopped) are left to the user.
