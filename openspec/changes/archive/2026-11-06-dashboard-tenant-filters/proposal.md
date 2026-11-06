# Proposal

## Why

The tenant grid shows only the first 10 tenants by name, so an operator can't reach the other 5 seed tenants, can't find one tenant by name or id, and can't answer "who is blocked right now?" without scanning every row (S5). The gateway's snapshot already filters and pages tenant rows (FR-14); the dashboard just never asks for it. This change is backlog item 15, `dashboard-tenant-filters`, and adds the filter controls and page navigation.

## What Changes

- Add filter controls above the tenant grid (FR-20 "Tenant grid", FR-14, S5): Name (contains, typed text), ID (exact, typed whole number), Tier (All or one of the loaded tiers), Status (All, OK, Near, Over), and a "Clear filters" button. The filters combine.
- Add page navigation below the grid: Previous / Next and "Page X of Y" from `tenants.total` and the page size of 10 (FR-14, FR-20).
- Send the filters and page with every snapshot poll as `name`, `id`, `tier`, `status`, `page` and `size` query parameters (FR-13, FR-19). Typed text is applied 300 ms after the last keystroke; a change of filters or page restarts the snapshot poll at once, cancelling a poll in flight without counting it as failed, so results follow within about one round trip (NFR-6).
- Keep filters and page across polls (FR-20). A filter change goes back to page 1. When the matching total shrinks below the current page (for example tenants leave the "over" set at a period reset, S3), the grid moves to the last page that has rows.
- While a poll for the current filters hasn't answered yet, the grid keeps the previous rows marked "Updating…" so they are never mistaken for results of the new filters.
- Never send a value the gateway would reject with `400` (which would count as a failed poll and could show "Gateway unreachable"): an ID that is not a whole number from 1 shows an inline message and is not applied.
- Disable the Status filter while metrics are unavailable (FR-15, FR-21 "Metrics unavailable", S7). If a status filter is already set when Redis goes down, the snapshot returns rows as unavailable; the grid then says the status filter needs metrics and offers "Clear status filter".
- Disable filters and page navigation while the gateway is unreachable or before the first snapshot, like the tier selectors (FR-21).

## Capabilities

### New Capabilities
- `dashboard-tenant-filters`: the filter controls and page navigation of the tenant grid — which filters exist, how they are validated and applied, how filters and page are kept across polls and corrected when results shrink, and how they behave while metrics are unavailable or the gateway is unreachable.

### Modified Capabilities
- `dashboard-polling`: the snapshot poll now carries the grid's filters and page as query parameters, and a change of them restarts the poll at once; the aborted poll is not a failed poll.
- `dashboard-tenant-grid`: "Unavailable rows are reported" now explains that the status filter needs metrics and offers to clear it.

## Impact

- **Code:** `/dashboard/src` only. Domain: tenant query model (filters, page, size 10), ID input parsing, page count and clamping, grid view gains pagination and "updating". Application: `SnapshotSource.fetchSnapshot` takes the query; `useDashboard` polls with the current query and records which query the shown snapshot answered; new `useTenantQuery` hook holds filters, draft text with a 300 ms debounce through the `Scheduler` port, and page. Adapter: `httpSnapshotSource` builds the query string. UI: `TenantFilters`, `Pager`, grid "Updating…" and status-filter hint; `App` wiring. `dashboard/README.md` updated.
- **APIs:** consumes the existing `GET /admin/snapshot` query parameters (`name`, `id`, `tier`, `status`, `page`, `size`); no gateway or contract change.
- **Dependencies:** none added.
- **Tests:** Vitest unit tests for the query model and ID parsing; hook tests with `ManualScheduler` (debounce, restart on change, clamping); adapter tests for the query string; React Testing Library tests for the controls and the unhappy paths (Redis down with a status filter, gateway unreachable, invalid ID) (NFR-9).

## Out of scope

- Sorting by anything but name, top-N by usage (docs/02 §6), and a page-size selector — the size stays 10 (FR-20).
- Keeping filters in the URL or across reloads.
- Cursor pagination and indexed search for 50,000 tenants (architecture §8, production direction).
- From docs/02 §6: authentication; push delivery (SSE/WebSocket) — polling stays (D9); a component library or design system.
- Demo calibration so every period shows tenants in all three states — `calibration-and-demo`.
- Gateway changes: the snapshot already filters, pages and reports rows unavailable for a status filter without Redis (FR-14, FR-15, D8).
