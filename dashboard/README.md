# Quota dashboard

Admin dashboard for the Tiered Tenant Quota Prototype. It polls the gateway's snapshot and health
endpoints and shows a header with gateway and enforcement status and the reset countdown, the
Traffic panel (current req/s and a 60-second trend) and the Period totals panel, together with the
live-data states: loading, gateway unreachable, reconnected, quota enforcement degraded and metrics
unavailable. The Tenants region shows a page of tenants with usage and status and lets the operator
change a tenant's tier, with name, id, tier and status filters and page navigation.

## Stack

- React + TypeScript (strict) + Vite, npm with a committed `package-lock.json`, Node `>=22`
- [oxlint](https://oxc.rs/docs/guide/usage/linter) (config in `.oxlintrc.json`)
- Vitest + React Testing Library in jsdom (`test/setup.ts`)
- Plain CSS, no component library
- Container: Node LTS build stage, unprivileged nginx runtime (`nginx/default.conf.template`)

## Source layout and layering

Dependencies point inward only. `npm run lint` fails when a rule below is broken.

| Folder | Layer | May import | Must not use |
|---|---|---|---|
| `src/domain/` | domain (pure TypeScript): models, polling state machine, status/percent/trend logic | only `domain/` | `react`, `react-dom`, `application/`, `adapters/`, `ui/`; globals `fetch`, `XMLHttpRequest`, `window`, `document`, `localStorage`, `setTimeout`, `setInterval` (timers and clocks are ports) |
| `src/application/` | use cases, hooks, ports | `domain/`, `react` | `adapters/`, `ui/`; `fetch`, `XMLHttpRequest` |
| `src/adapters/api/` | outbound HTTP adapters implementing application ports | `domain/`, `application/` | `react`, `react-dom`, `ui/` |
| `src/adapters/time/` | clock and timer adapter implementing the `Scheduler` port | `domain/`, `application/` | `react`, `react-dom`, `ui/` |
| `src/ui/` (`App.tsx`, `index.css`), `src/ui/components/` | presentational UI | `domain/`, `application/`, `react` | `adapters/`; `fetch`, `XMLHttpRequest` (components never call `fetch`) |
| `src/main.tsx` | composition root | everything | — |

`main.tsx` creates the adapters and hands them to the UI via props/context; UI components never
construct adapters. The rules match relative import paths at any depth (`**/adapters/**`); no
path aliases are configured, so do not add one that would bypass them.

Empty layer folders are kept in git with a `.gitkeep` file. Delete a folder's `.gitkeep` when
the first real file lands in it.

## Tests

Tests never sit next to the source. They live in `test/`, which mirrors `src/` folder by folder,
with one `<module>.test.ts(x)` per tested module:

| Folder | Contents |
|---|---|
| `test/domain/`, `test/application/`, `test/adapters/api/`, `test/adapters/time/`, `test/ui/`, `test/ui/components/` | tests of the module with the same path under `src/`, e.g. `src/domain/connection.ts` → `test/domain/connection.test.ts` |
| `test/support/` | shared test doubles and fixtures: `ManualScheduler`, fake snapshot and health sources, sample snapshots, deferred promises |
| `test/setup.ts` | Vitest setup (jest-dom matchers, cleanup) |

Vitest only collects `test/**/*.test.{ts,tsx}`. `tsconfig.test.json` type-checks the tree as part of
`npm run build`, and the layering lint rules of each `src/` folder also apply to its `test/`
counterpart (a test in `test/domain/` may not import React, for example).

## Live data and states

`useDashboard(query)` (`src/application/`) runs two polls through the `Scheduler` port and returns one
`DashboardView` that the UI components read:

| Poll | Path | Every | Timeout |
|---|---|---|---|
| Snapshot | `GET /admin/snapshot?…&page=…&size=10` (filters and page, see below) | 1 s | 2 s |
| Health | `GET /actuator/health` | 5 s | 2 s |

Each poll starts immediately on open, never overlaps itself (the next one starts 1 s / 5 s after
the previous start, or right after a slower answer) and stops when the app unmounts. A snapshot
poll restarts at once when the filters or page change. A snapshot
poll fails on a network error, a timeout, a non-2xx status or a body without `period` /
`tenants`; `"metrics":null` is a success.

| State | Trigger | Shown |
|---|---|---|
| Loading | no snapshot received yet | "Loading dashboard…" instead of the regions, no numbers |
| Gateway unreachable | 2 failed snapshot polls in a row | red banner; last data greyed out with "last updated N s ago"; tier controls disabled; polling continues |
| Reconnected | first successful snapshot after unreachable | banner and greying removed, "Reconnected" for about 3 s |
| Enforcement degraded | health reports `components.redis.status` `DOWN` (200 or 503) | yellow "Quota enforcement degraded: no new blocks, counting paused" banner |
| Metrics unavailable | snapshot has `"metrics":null` | "Metrics unavailable" in the Traffic and Period totals regions; tier controls stay enabled |

A failed health poll (gateway down, `502`) clears the degraded banner and never counts towards
"unreachable". The states are covered by `test/ui/App.test.tsx` with fake sources. To see them
against the real stack, start it and stop Redis or the gateway with the scripts in `/scripts`.

## Header and traffic panels

| Part | Shows | Source |
|---|---|---|
| Header: Gateway | Connecting (no snapshot yet), Connected, Unreachable (2 failed snapshot polls) | connection state |
| Header: Enforcement | OK (Redis `UP`), Degraded (Redis `DOWN`), Unknown (no health answer yet or health poll failed) | health poll |
| Header: Resets in | `period.secondsLeft` minus whole seconds since that snapshot arrived, as `m:ss` or `h:mm:ss`; "—" before the first snapshot and while the gateway is unreachable | snapshot `period` |
| Traffic | allowed and rejected req/s of the newest `perSecond` entry ("—" without entries), and an SVG trend of all 60 entries with both lines on one scale from 0 | `metrics.perSecond` |
| Period totals | allowed and rejected totals of the period, tenants near and over their limit | `metrics.totals`, `tenantsNear`, `tenantsOver` |

The countdown uses the gateway's `secondsLeft`, not `endsAt` against the browser clock, so a skewed
browser clock can't shift it; it works while Redis is down because the period needs no Redis. With
`"metrics":null` both panels show only "Metrics unavailable". While the gateway is unreachable they
keep the last numbers and trend, greyed out. The arithmetic lives in `src/domain/`
(`resetCountdown.ts`, `trafficSummary.ts`, `trendGeometry.ts`); formatting in `src/ui/format.ts`.
To watch the countdown restart (S3), Redis outages (S7) and gateway outages (S8) against the real
stack, use the scripts in `/scripts`.

## Tenant grid and tier changes

The Tenants region shows the snapshot's page of tenants (up to 10 rows sorted by name by the
gateway) with the columns Tenant, ID, Tier, Used, Rejected and Status, and "Showing N of T
tenants" below. Used reads `used / limit (P%)`, with P rounded down and above 100 when a tenant
overshot its limit; Status reads OK, Near or Over. With `"metrics":null` the rows keep name, id and
tier and show "—" for Used, Rejected and Status; `rowsAvailable: false` shows "Tenant rows
unavailable: the status filter needs metrics"; an empty page shows "No tenants".

| Part | Behaviour |
|---|---|
| Tier list | `GET /admin/tiers` on start, retried every 5 s (2 s timeout each) until it succeeds; options read "Silver (1,000)". Until then each selector shows only the row's tier and is disabled |
| Tier change | choosing another tier sends `PUT /admin/tenants/{id}/tier` with `{"tier": name}`; choosing the current tier sends nothing |
| Saving | the row's selector shows the requested tier, is disabled and "Saving…" is shown; other rows stay usable |
| Success | the row shows the answer's tier and limit until a snapshot reports that tier, so an older snapshot in flight can't flip it back |
| Failure | the selector reverts to the latest snapshot's tier and the row shows "Tier change failed: …" — tenant not found (404), tier rejected by the gateway (400), gateway error (status), no answer within 2 s, or gateway unreachable — until the next change on that row |
| Connection | selectors are disabled before the first snapshot and while the gateway is unreachable (rows greyed with the other regions), and enabled while Redis is down or metrics are unavailable |

The per-row state is the pure reducer `src/domain/tierChange.ts`, the rows are built by
`src/domain/tenantGrid.ts`, and `src/application/useTenantGrid.ts` sends the changes through the
`TierAdministration` port (`src/adapters/api/httpTierAdministration.ts`). To try a tier change
against the real stack (S6), with Redis stopped (S7) or with the gateway stopped (S8), use the
scripts in `/scripts`.

## Filters and pages

Above the grid sit the filters Name (contains), ID (exact), Tier ("All tiers" plus the loaded tiers)
and Status ("All statuses", OK, Near, Over) and a "Clear filters" button; below it the pager with
Previous, "Page X of Y" and Next. Pages hold 10 tenants. Filters and page live in
`useTenantQuery()` (`src/application/`) as one `TenantQuery` (`src/domain/tenantQuery.ts`) and
are sent with every snapshot poll as `name`, `id`, `tier`, `status`, `page` and `size=10`,
omitting empty filters (`src/adapters/api/httpSnapshotSource.ts`).

| Rule | Behaviour |
|---|---|
| Typing | Name and ID are applied 300 ms after the last keystroke; Tier, Status, Clear and paging apply at once |
| ID | must be a whole number from 1; anything else shows "ID must be a whole number from 1" and keeps the previously applied filters, so the gateway never answers `400` |
| First page | any filter change requests page 1 again |
| Kept | filters and page stay across polls, tier changes, reconnects and metrics outages |
| Restart | a new query restarts the snapshot poll at once; the cancelled request is not a failed poll |
| Updating | until the snapshot for the new query answers, the previous rows are greyed with "Updating…" |
| Shrinking | when the shown page is past the last page (e.g. tenants leave "over" at a reset), the grid moves to the last page, or the first when nothing matches |
| No metrics | the Status filter is disabled and keeps its value; with a status filter set the gateway reports rows unavailable and the grid offers "Clear status filter" |
| Unreachable | filters, "Clear filters" and the pager are disabled and keep their values until a snapshot succeeds |

`useDashboardScreen()` composes the query, the polls, the tier list and the grid for `App`. To
filter by status "over" and page through live data (S5), or to try the status filter with Redis
stopped (S7), run the stack and use the scripts in `/scripts`.

## Scripts

Install dependencies first:

```bash
npm ci
```

| Command | What it does |
|---|---|
| `npm run dev` | Vite dev server on `http://localhost:5173` (fails if the port is taken) |
| `npm run build` | Type-check (`tsc -b`) and build static files into `dist/`; fails on type errors |
| `npm run lint` | oxlint, including the layering rules |
| `npm test` | Run the tests once, headless (Vitest + React Testing Library) |
| `npm run test:watch` | Run the tests in watch mode |
| `npm run preview` | Serve the built `dist/` locally |

## Gateway in development

The dev server forwards `/admin/**` and `/actuator/health` to the gateway, so the browser talks
to one origin (no CORS), just like in the container. Nothing else is forwarded.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` | Gateway the dev server proxies to |

`GATEWAY_URL` is read by `vite.config.ts` from the shell environment (it is not a `VITE_`
variable and never reaches the browser bundle); see `.env.example`. Example with a gateway on
port 9090:

```bash
GATEWAY_URL=http://localhost:9090 npm run dev
```

With the gateway running (`./gradlew :bootstrap:bootRun` in `/gateway`):

```bash
curl http://localhost:5173/actuator/health
```

returns the gateway's `{"status":"UP"}`. Without a gateway it returns `502`.

## Docker

Build the image in this folder (only Docker is needed on the host):

```bash
docker build -t quota-dashboard:local .
```

The container serves the app on port `8080` as the non-root `nginx` user and has a healthcheck
on `GET /` that does not depend on the gateway. nginx forwards `/admin/**` and
`/actuator/health` to the gateway with path, query, method and body unchanged (2 s connect /
5 s read timeouts); every other path, including `/api/**` and other `/actuator/*` paths, gets
the app shell. The gateway address is resolved per request, so the dashboard starts without a
gateway and picks it up when it appears.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_UPSTREAM` | `gateway:8080` | Gateway `host:port` that nginx proxies to |
| `NGINX_RESOLVER` | `127.0.0.11` | DNS server nginx uses to resolve the gateway host (Docker's embedded DNS on user-defined networks) |

### Standalone

```bash
docker run -d --name quota-dashboard -p 3000:8080 quota-dashboard:local
```

`http://localhost:3000/` shows the app shell and the container becomes `healthy`. On the
default bridge network there is no gateway to reach, so `/actuator/health` and `/admin/**`
answer `502` after about 2 s. Stop it with:

```bash
docker rm -f quota-dashboard
```

### Networked with the gateway

Put both containers on one user-defined network, with the gateway named `gateway` (build
`quota-gateway:local` first, see `/gateway/README.md`):

```bash
docker network create quota-net
```

```bash
docker run -d --name gateway --network quota-net quota-gateway:local
```

```bash
docker run -d --name quota-dashboard --network quota-net -p 3000:8080 quota-dashboard:local
```

```bash
curl http://localhost:3000/actuator/health
```

returns `{"status":"UP"}` once the gateway has started. Stopping and starting the gateway does
not require restarting the dashboard. A gateway with another name or port is set with
`-e GATEWAY_UPSTREAM=<host>:<port>`. Clean up with:

```bash
docker rm -f quota-dashboard gateway
```

```bash
docker network rm quota-net
```
