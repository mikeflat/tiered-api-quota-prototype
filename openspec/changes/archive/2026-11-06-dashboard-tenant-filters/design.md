# Design

## Context

Current dashboard (`/dashboard/src`, after `dashboard-tenant-grid`):

- `SnapshotSource.fetchSnapshot(signal)` requests `GET /admin/snapshot` without parameters; `useDashboard()` polls it through `usePolling`, whose effect restarts the loop whenever `task`, interval, timeout or scheduler change. `startPolling`'s `stop()` aborts the request in flight and ignores its outcome, so a restart never reports a failure.
- `domain/connection.ts` keeps the shown snapshot as `ReceivedSnapshot { snapshot, receivedAt }`; `DashboardView` exposes `snapshot`, `metricsAvailable` and `tierControlsEnabled` (true only while live).
- `useTenantGrid(view)` builds `TenantGridView` with `tenantGridOf(page, changes, tiers, controlsEnabled)` and calls `useTiers()` internally; `App` renders `DashboardScreen` with `view`, `grid` and `onTierChange`.
- Gateway contract (unchanged, `admin-snapshot` spec): `name`, `id` (positive integer), `tier`, `status` (`ok`/`near`/`over`), `page` (≥ 0), `size` (1..100); invalid values → `400`; a page past the end → `200` with empty rows and the real `total`; status filter without Redis → `rowsAvailable: false`.
- `dashboard-polling` treats any non-2xx snapshot answer as a failed poll, so a `400` caused by a bad filter would push the dashboard towards "Gateway unreachable".

Requirements: `specs/dashboard-tenant-filters`, `specs/dashboard-polling` (added), `specs/dashboard-tenant-grid` (modified). Motivation: proposal.md.

## Goals / Non-Goals

**Goals:**
- One immutable `TenantQuery` (filters + page) is the single source for the request, the controls and the "is this result current?" check.
- A query change reuses the existing restart behaviour of `usePolling` instead of adding a second polling mechanism.
- No input can produce a `400` from the snapshot.

**Non-Goals:**
- No change to `startPolling`, poll cadence or the connection state machine's transitions.
- No page-size control, no URL state, no sorting options.

## Decisions

### D-1. Module map by layer

| Layer | Module (under `dashboard/src/`) | Contents | Depends on |
|---|---|---|---|
| domain | `domain/tenantQuery.ts` | `TenantFilters { name, id: number \| null, tier: string \| null, status: TenantStatus \| null }`; `TenantQuery { filters, page }`; `tenantPageSize = 10`; `noFilters`, `initialTenantQuery`; `withFilters(query, filters)` (page → 0), `withPage(query, page)`, `sameQuery(a, b)` | `snapshot.ts` |
| domain | `domain/idInput.ts` | `parseIdInput(text)` → `{ kind: 'empty' }` / `{ kind: 'valid', id }` / `{ kind: 'invalid' }`; valid = only digits, value ≥ 1 and a safe integer | — |
| domain | `domain/pagination.ts` | `pageCountOf(total, size)` = `max(1, ceil(total / size))`; `correctedPageOf(page: TenantPage)` → the last page index (`pageCountOf(total) − 1`) when `page` is past it, else `null` | `snapshot.ts`, `tenantQuery.ts` |
| domain | `domain/filterControls.ts` | `FilterDrafts { nameText, idText }`; `FilterControlsView` (texts, `idInvalid`, tier, status, tier options, `enabled`, `statusEnabled`); `filterControlsOf(drafts, query, tiers, controlsEnabled, metricsAvailable)` | above, `tiers.ts` |
| domain | `domain/connection.ts` (changed) | `ReceivedSnapshot` gains `query`; event `snapshotReceived` carries it | `tenantQuery.ts` |
| domain | `domain/dashboardView.ts` (changed) | `DashboardView` gains `shownQuery: TenantQuery \| null` | — |
| domain | `domain/tenantGrid.ts` (changed) | `tenantGridOf({ page, changes, tiers, controlsEnabled, updating })`; the `rows` view gains `pageNumber`, `pageCount`, `canPrevious`, `canNext` (both false unless controls are enabled), `updating`; the `unavailable` view gains `canClearStatus` | `pagination.ts` |
| application | `application/ports.ts` (changed) | `SnapshotSource.fetchSnapshot(query: TenantQuery, signal)` | domain |
| application | `application/useDashboard.ts` (changed) | `useDashboard(query)`: the poll task is memoised on `[snapshots, query]`, so a new query restarts `usePolling` (D-2); `onSuccess` dispatches the snapshot with the query of that loop | `usePolling` |
| application | `application/useTenantQuery.ts` | holds applied `TenantQuery` and `FilterDrafts`; `setNameText` / `setIdText` apply 300 ms after the last call through `Scheduler.schedule` (previous timer cancelled; invalid ID keeps the applied id); `setTier`, `setStatus`, `clearFilters`, `clearStatus`, `nextPage`, `previousPage`, `goToPage` apply at once; pending timers cancelled on unmount | `Scheduler`, domain |
| application | `application/useTenantGrid.ts` (changed) | `useTenantGrid(view, tiers, query)`; `updating = view.shownQuery !== null && !sameQuery(view.shownQuery, query)`; no longer calls `useTiers` | — |
| application | `application/useDashboardScreen.ts` | composes `useTenantQuery`, `useDashboard(query)`, `useTiers`, `useTenantGrid`; an effect calls `goToPage` when `correctedPageOf` of a current (non-updating) snapshot returns a page (D-4); returns `{ view, grid, filters, actions }` | all above |
| adapter (out, HTTP) | `adapters/api/httpSnapshotSource.ts` (changed) | builds `URLSearchParams` in the order `name`, `id`, `tier`, `status`, `page`, `size`, omitting empty filters; requests `/admin/snapshot?…` | domain |
| UI | `ui/components/TenantFilters.tsx` | labelled `<input type="search">` Name, `<input inputmode="numeric">` ID with the inline message, `<select>` Tier and Status, "Clear filters" button | domain types |
| UI | `ui/components/Pager.tsx` | "Previous", "Page X of Y", "Next" | — |
| UI | `ui/components/TenantGrid.tsx` (changed) | renders "Updating…" and the `is-updating` greying; unavailable message with "Clear status filter"; renders `Pager` | — |
| UI | `ui/App.tsx`, `DashboardScreen.tsx`, `DataRegions.tsx` (changed) | `App` uses `useDashboardScreen()`; the Tenants region renders `TenantFilters` above `TenantGrid` | above |

`test/support/fakeSources.ts`: `FakeSnapshotSource` records the query of each request. Existing `useTenantGrid` tests pass tiers in directly.

*Alternative:* keep filters inside `useTenantGrid` — rejected: `useDashboard` needs the query before the grid exists, and the screen-level hook is the one place that sees both.

### D-2. Restarting the poll on a query change

`useDashboard(query)` memoises `task = (signal) => snapshots.fetchSnapshot(query, signal)` on `[snapshots, query]`. `usePolling`'s effect depends on `task`, so a new query runs the cleanup (`stop()`: abort in flight, cancel timers, ignore its outcome) and starts a new loop whose first poll is immediate. This gives exactly the behaviour in the `dashboard-polling` delta: no overlap, the cancelled request never reaches `onFailure`, the consecutive-failure count is untouched, and the 1 s cadence continues from the new start. `TenantQuery` objects are only replaced on real changes, so identity comparison is enough to avoid spurious restarts.

The query is captured in the loop's closure and passed with `snapshotReceived`, so `ReceivedSnapshot.query` always names the request that produced the snapshot.

*Alternative:* add a `pollNow()` to `startPolling` and read the query from a ref — rejected: it changes a tested module and needs extra rules for "in flight with the old query", which the restart already handles.

### D-3. Debounce and ID validation

Typing updates `FilterDrafts` immediately (the inputs are controlled) and schedules one apply 300 ms later; each keystroke cancels the previous timer. On apply, the name is trimmed; the ID is parsed with `parseIdInput`: `empty` → `id: null`, `valid` → `id`, `invalid` → the applied query is left unchanged and `idInvalid` is shown. `idInvalid` is derived from the draft text, so the message appears and disappears with the text and never depends on a poll. Tier, Status and "Clear status filter" apply at once and cancel any pending typed apply after folding it in, so the typed text is not lost; "Clear filters" also clears the drafts. Paging applies at once and leaves a pending typed apply running: folding a text change into a page click would reset the page to 0 and undo the click.

*Alternative:* `<input type="number">` — rejected: it accepts `1e3`, `-1` and `1.5`, and browsers report invalid text as an empty value, hiding the error.

### D-4. Following a shrinking result

`correctedPageOf` only acts on a snapshot that answered the applied query (not `updating`) whose `page` is past the last page, where the last page is `pageCountOf(total) − 1` (so `0` when `total = 0`). `useDashboardScreen` then calls `goToPage(last)`, which restarts the poll with that page. With `total = 0` this returns to page 0, so the pager reads "Page 1 of 1" next to "No tenants".

### D-5. Status filter without metrics

`statusEnabled = controlsEnabled && view.metricsAvailable`. The applied status is kept, because removing it silently would change what the operator asked for; the gateway answers `rowsAvailable: false` (FR-15) and the grid offers "Clear status filter", which calls `clearStatus()`. "Clear filters" stays enabled while live, so it also works.

## Risks / Trade-offs

- [Each query change aborts a request; fast clicking on "Next" sends many short-lived requests] → each restart cancels the previous request first, so at most one is in flight; the debounce covers typing.
- [While "Updating…" the rows belong to the previous query for up to one round trip] → they are greyed and labelled; tier changes on those rows still target the right tenant ids.
- [Clamping uses one extra request after a reset] → only when the shown page is past the end; at most one per shrink.
- [ID parsing rejects leading `+` or spaces inside the number] → the message explains the expected input; leading/trailing spaces are trimmed.

## Migration Plan

Dashboard-only, no contract change. The default query sends `?page=0&size=10`, which the gateway already treats as the default. Rolled out by rebuilding the dashboard image; rollback is the previous image. Manual S5 (filter by status "over", page through results while data refreshes) and S7 (status filter with Redis stopped) checks against the running stack are left to the user.
