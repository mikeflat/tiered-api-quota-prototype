# Backlog: Tiered Tenant Quota Prototype

Each item is meant to become one OpenSpec change. Names in backticks are suggested change ids. Details are settled inside each change; see [requirements.md](requirements.md) and [architecture.md](architecture.md).

## Phase 1: Foundation

1. ✅ **Done** `bootstrap-skeleton` — Create the repository layout with an empty gateway, dashboard and generator, plus docker compose with Redis and scripts in `/scripts` that start and stop the whole stack or each component individually. Move the initial documents into `/docs`. Each service starts and answers a trivial request.
2. ✅ **Done** `tenant-registry-and-config` — Load tenants from `tenants.json` into an in-memory registry and read tier limits, period length and near threshold from configuration. Seed 15 tenants (8 Bronze, 5 Silver, 2 Gold).
3. ✅ **Done** `stub-business-endpoint` — Add `GET /api/v1/resource` returning a mock payload, behind a filter that requires a known `X-Tenant-Id` and returns 400 otherwise.

## Phase 2: Quota enforcement

4. ✅ **Done** `async-usage-recording` — Add a bounded event queue and a writer thread that applies each request event to Redis with a Lua script (increment `used` or `rejected`, return `used`), dropping and counting events when the queue is full.
5. ✅ **Done** `blocked-set-enforcement` — Keep a per-period in-memory blocked set fed by the writer when `used ≥ limit`, reject blocked tenants with 429 and `Retry-After`, and discard the set at the period boundary. Test that blocking happens with bounded overshoot.
6. ✅ **Done** `redis-failure-and-health` — Keep serving and enforcing from the blocked set when Redis fails, log failures and drops, and expose Redis status in `/actuator/health` with a short timeout. Test with Redis stopped mid-run.
7. ✅ **Done** `traffic-metrics-recording` — Extend the record script with global per-second and per-period allowed/rejected counts and near/over set maintenance against the tenant's current limit.

## Phase 3: Admin API

8. ✅ **Done** `admin-tenants-and-tiers` — Add `GET /admin/tiers` and `PUT /admin/tenants/{id}/tier`; a tier change removes the tenant from the blocked set so its next request is re-evaluated in Redis, and works while Redis is down.
9. ✅ **Done** `admin-snapshot` — Add `GET /admin/snapshot` that returns the cached global metrics (rebuilt once per second) plus the first page of tenants by name with usage, and marks metrics unavailable when Redis can't be read.
10. ✅ **Done** `tenant-search-and-pagination` — Add combinable filters (name contains, exact id, tier, status from the near/over sets) and offset pagination with a total count to the snapshot rows. The status filter is reported unavailable when Redis is down.

## Phase 4: Traffic

11. ✅ **Done** `traffic-generator` — Implement the Node.js generator that sends weighted, rate-paced traffic for tenants from `tenants.json`, survives gateway outages, and logs periodic summaries. Add it to docker compose.

## Phase 5: Dashboard

12. ✅ **Done** `dashboard-polling-and-states` — Build the app shell with polling for snapshot and health, and the unhappy states: loading, gateway unreachable, reconnected, enforcement degraded, metrics unavailable.
13. ✅ **Done** `dashboard-traffic-panels` — Show the header (statuses, reset countdown), current allowed/rejected req/s with a 60-second SVG trend, period totals and near/over counts.
14. ✅ **Done** `dashboard-tenant-grid` — Show a page of 10 tenants with usage, rejected count, status, and a tier selector with inline error and revert on failure.
15. ✅ **Done** `dashboard-tenant-filters` — Add filter controls (name, id, tier, status) and page navigation to the grid, keeping filters and page across polls and disabling the status filter while metrics are unavailable.

## Phase 6: Demo readiness

16. `calibration-and-demo` — Tune tier limits and generator weights so every period shows tenants in all three states, and write a README with how to run the stack and demo the scenarios using the component scripts (stop Redis, stop the gateway, change a tier, filter by status).

## Enhancements (not planned)

- Specified usage semantics on tier change: upgrade-resume and downgrade-over-limit display, with tests.
- Top-N tenants sorted by usage.
- SSE push delivery; separate admin service; real upstream proxying.
