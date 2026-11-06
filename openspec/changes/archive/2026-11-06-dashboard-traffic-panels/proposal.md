# Proposal

## Why

The dashboard now polls the snapshot and health and renders every unhappy state, but its Traffic and Period totals regions are empty frames and the header holds only the title. An operator still can't see live req/s, the trend, how much of the period has been used or when the next reset comes (S3, S4). This change is backlog item 13, `dashboard-traffic-panels`, and fills the header and the two metric regions from the data the polls already deliver.

## What Changes

- Add header statuses (FR-20 "Header"): gateway connection status (Connecting / Connected / Unreachable, from the existing connection state), quota enforcement status (OK / Degraded / Unknown, from the existing health poll) and a countdown to the next reset from `period.secondsLeft`, ticking with the existing 1 s clock between polls. The countdown restarts at the period boundary (S3) and is shown as "—" before the first snapshot and while the gateway is unreachable, so no stale time pretends to be live.
- Fill the Traffic region (FR-20 "Traffic", S1, S2, S4): allowed and rejected req/s of the last complete second (the newest `metrics.perSecond` entry), and a hand-written SVG sparkline of the 60-second trend with one line for allowed and one for rejected on a shared scale from zero (architecture §2.3). No chart library (FR-22).
- Fill the Period totals region (FR-20 "Period totals"): allowed and rejected totals of the current period, and the number of tenants near and over their limit (`metrics.totals`, `tenantsNear`, `tenantsOver`).
- Keep the existing unhappy states intact: before the first snapshot nothing is drawn (FR-21 "Initial loading"); with `"metrics":null` both regions show only "Metrics unavailable" while the header and countdown still work (FR-15, S7, the period needs no Redis); while the gateway is unreachable the panels keep the last known numbers greyed out (FR-21, S8).
- Extend the dashboard view model with the header statuses and the reset countdown, and add pure domain functions for the traffic summary and the sparkline geometry, so the UI only formats and draws.

## Capabilities

### New Capabilities
- `dashboard-header`: what the dashboard header shows — gateway connection status, quota enforcement status and the countdown to the next quota reset — and how each behaves while loading, unreachable, degraded and across a period boundary.
- `dashboard-traffic`: what the Traffic and Period totals regions show — current allowed/rejected req/s, the 60-second allowed/rejected trend, period totals and near/over tenant counts — and how they behave with missing or stale metrics.

### Modified Capabilities
None. The loading, unreachable, reconnected, degraded and metrics-unavailable requirements in `dashboard-states` stay as they are; the new panels are drawn inside the regions those requirements already govern.

## Impact

- **Code:** `/dashboard/src` only. Domain: new traffic summary, trend geometry and reset countdown functions; `DashboardView` gains connection status, enforcement status and seconds until reset. UI: header status bar, Traffic panel with the SVG sparkline, Period totals panel, number formatting; `DataRegions` renders them; styles in `ui/index.css`. `dashboard/README.md` updated.
- **APIs:** consumes the existing `GET /admin/snapshot` fields `period`, `metrics.perSecond`, `metrics.totals`, `metrics.tenantsNear`, `metrics.tenantsOver` and the existing health poll; no gateway or contract change.
- **Dependencies:** none added (no chart or UI library).
- **Tests:** Vitest unit tests for the new domain functions; React Testing Library tests for the header, both panels and their behaviour while loading, unreachable and with metrics unavailable (NFR-9).

## Out of scope

- Tenant grid with usage, rejected count, status and tier selector, and the "Tier change failed" state — `dashboard-tenant-grid`.
- Filters, page navigation and disabling the status filter while metrics are unavailable — `dashboard-tenant-filters`.
- Tuning tier limits and generator weights so the panels show all three states — `calibration-and-demo`.
- Top-N tenants sorted by usage (docs/02 §6, enhancement).
- From docs/02 §6: push delivery (SSE/WebSocket) — polling stays (D9); authentication of the dashboard; a component library, chart library or design system; controlling the generator from the dashboard.
- Gateway changes: the snapshot already carries every value shown here (FR-13).
