# Proposal

## Why

The dashboard now shows live traffic, totals and the header, but its Tenants region is still an empty frame: an operator can't see which tenants are near or over their limit, and can't change a tier from the screen even though the gateway already serves `PUT /admin/tenants/{id}/tier` (S4, S6). This change is backlog item 14, `dashboard-tenant-grid`, and fills that region with the page of tenant rows the snapshot already delivers, plus the tier selector.

## What Changes

- Show the snapshot's tenant page in the Tenants region as a table of up to 10 rows sorted by name, as delivered by the gateway: tenant name, id, tier, used / limit with a percentage, rejected count, and a status of OK / Near / Over shown as text and colour (FR-13, FR-20 "Tenant grid", FR-22, S4). Below the table, "Showing N of T tenants" from the page's `total`.
- Load the tier list with its limits from `GET /admin/tiers` once the dashboard starts and retry every 5 s until it succeeds, so the selector offers every configured tier with its limit (FR-16, NFR-8).
- Add a tier selector per row that sends `PUT /admin/tenants/{id}/tier` (FR-11, FR-16, S6). While the request is in flight that row's selector is disabled and marked "Saving…". On success the row shows the new tier until the next snapshot catches up. On an error status, a network error or no answer within 2 s, the row shows an inline error and the selector reverts to the tier from the latest snapshot (FR-21 "Tier change failed").
- Tie the selectors to the existing states (FR-21): disabled while the gateway is unreachable (greyed with the rest of the data); enabled while metrics are unavailable or enforcement is degraded, because tier changes work without Redis (D6, S7). With `"metrics":null` the grid still shows names, ids and tiers, and "—" for used, rejected and status.
- Add the tier-administration port and its HTTP adapter for `GET /admin/tiers` and `PUT /admin/tenants/{id}/tier`, wired in `main.tsx` (Clean Architecture; components never call `fetch`, D13: same origin through nginx).

## Capabilities

### New Capabilities
- `dashboard-tenant-grid`: what the Tenants region shows — the page of tenant rows with usage, percentage, rejected count and status, the tier list, the per-row tier selector and its saving, success and failure behaviour, and how rows look while metrics are unavailable or the gateway is unreachable.

### Modified Capabilities
- `dashboard-states`: the requirement "Tier controls are disabled while unreachable" said the controls arrive with the tenant grid and only defined the enabled flag; it now applies to the real tier selectors in the grid.

## Impact

- **Code:** `/dashboard/src` only. Domain: tier and tier-change models, row usage percentage and status label logic, per-row tier change state. Application: `TierAdministration` port, tier list loading with retry, tier change use case with a 2 s timeout, both exposed through hooks; `DashboardPorts` gains the new port. Adapter: `adapters/api/httpTierAdministration.ts`. UI: `TenantGrid`, `TenantRow`, `TierSelector` components, `DataRegions` renders the grid; styles in `ui/index.css`. `main.tsx` wiring. `dashboard/README.md` updated.
- **APIs:** consumes the existing `GET /admin/tiers`, `PUT /admin/tenants/{id}/tier` and the `tenants` part of `GET /admin/snapshot` (no query parameters yet); no gateway or contract change.
- **Dependencies:** none added.
- **Tests:** Vitest unit tests for domain and use cases with `ManualScheduler`; adapter tests with a stubbed `fetch`; React Testing Library tests for the grid, the selector and its unhappy paths (failed and timed-out change, unreachable, metrics unavailable) (NFR-9).

## Out of scope

- Filter controls (name, id, tier, status), page navigation, passing query parameters to the snapshot poll, and disabling the status filter while metrics are unavailable — `dashboard-tenant-filters`. This change shows page 0 only; `rowsAvailable: false` (only produced by a status filter) is shown as a plain "Tenant rows unavailable" message.
- Specified usage semantics on tier change (upgrade-resume, downgrade-over-limit display) — docs/02 §6, preferable enhancement; the grid shows what the snapshot reports.
- Editing tier limits and an audit log of tier changes; persisting tier changes (docs/02 §6, D11).
- Top-N tenants sorted by usage (docs/02 §6).
- From docs/02 §6: authentication of the dashboard; push delivery (SSE/WebSocket) — polling stays (D9); a component library or design system.
- Gateway changes: the admin API already provides everything used here (FR-13, FR-16).
