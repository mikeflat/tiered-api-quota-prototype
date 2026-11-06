# Proposal

## Why

The gateway now serves `GET /admin/snapshot` and `/actuator/health`, but the dashboard is still a static skeleton that shows "No data yet." Backlog items 13–15 (traffic panels, tenant grid, filters) all need live data and a single, tested notion of "is the gateway reachable, is enforcement degraded, are metrics available". This change is backlog item 12, `dashboard-polling-and-states`, and builds that foundation before any panel is drawn.

## What Changes

- Add the dashboard's polling engine: the snapshot is polled every 1 s and health every 5 s, each with a 2 s timeout, and polls of the same kind never overlap (FR-19, NFR-6, D9).
- Add a pure polling/connection state machine in the dashboard domain: initial loading, live, gateway unreachable after 2 consecutive failed snapshot polls, recovered with a brief "Reconnected" notice (FR-21, S8, S9).
- Derive quota enforcement status from the health poll: Redis `DOWN` → degraded; an unreadable health poll → unknown, with no banner (FR-17, FR-21, S7, D10).
- Detect "metrics unavailable" from `metrics: null` in the snapshot (FR-15, FR-21, S7).
- Replace the skeleton shell with an app shell that renders these states: a loading indicator instead of panels before the first snapshot; a red "Gateway unreachable" banner with the last known data greyed out and "last updated N s ago"; a "Reconnected" notice; a yellow "Quota enforcement degraded: no new blocks, counting paused" banner; "Metrics unavailable" in the traffic and totals regions (FR-20, FR-21, FR-22).
- Expose the derived view state (connection, enforcement, metrics availability, whether tier controls are enabled) through an application hook so items 13–15 render panels, header and grid from it without re-implementing polling.
- Add HTTP adapters for `GET /admin/snapshot` and `GET /actuator/health`, plus clock/timer adapters, wired in `main.tsx` (Clean Architecture; components never call `fetch`).

## Capabilities

### New Capabilities
- `dashboard-polling`: how the dashboard fetches live data — snapshot and health cadence, per-poll timeout, no overlapping polls of one kind, what counts as a failed poll, and stopping polls when the app unmounts.
- `dashboard-states`: what the dashboard shows for each connection, enforcement and metrics state — initial loading, gateway unreachable with stale data, reconnected, enforcement degraded, metrics unavailable — and which controls are enabled in each.

### Modified Capabilities
- `dashboard-runtime`: the "Dashboard serves its app shell" requirement no longer describes a static skeleton with "no metric values"; the shell now shows a loading indicator until the first snapshot arrives and never shows placeholder zeros.

## Impact

- **Code:** `/dashboard/src` only — new `domain/` (snapshot and health models, connection state machine, poll delay), `application/` (ports, `usePolling`, `useDashboard`), `adapters/api/` (snapshot and health clients), `adapters/time/` (browser clock and timer), `ui/` (`App.tsx`, banners, loading indicator, stale note, region placeholders) and `main.tsx` wiring. The `.gitkeep` files in the folders that receive code are removed. `dashboard/README.md` updated.
- **APIs:** consumes the existing `GET /admin/snapshot` (no query parameters yet) and `GET /actuator/health`; no gateway change.
- **Dependencies:** none added.
- **Tests:** Vitest unit tests for domain and hooks with fake timers; adapter tests with a stubbed `fetch`; React Testing Library tests for every state in the FR-21 table that belongs to this item (NFR-9).

## Out of scope

- Header contents beyond the banners (connection and enforcement status labels, reset countdown), traffic numbers, the 60-second SVG trend, period totals and near/over counts — `dashboard-traffic-panels`.
- Tenant grid, tier selector, tier change and its "Tier change failed" state — `dashboard-tenant-grid`. This change only exposes whether tier controls are enabled.
- Filters, page navigation and passing query parameters to the snapshot poll, and disabling the status filter while metrics are unavailable — `dashboard-tenant-filters`.
- From docs/02 §6: push delivery (SSE/WebSocket) — polling is used (D9); authentication of the dashboard; a component library or design system; controlling the generator from the dashboard.
