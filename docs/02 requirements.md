# Requirements: Tiered Tenant Quota Prototype

Source task: [task.md](task.md). Architecture: [architecture.md](architecture.md). Plan: [backlog.md](backlog.md).

## 1. Goal

A working prototype of three parts:

1. A **quota gateway** that enforces a per-tenant quota by tier using a fixed window, and rejects requests over the limit with HTTP 429.
2. A **React admin dashboard** that shows live traffic, tenants close to or over their limit, rejections, service health, and lets an operator change a tenant's tier.
3. A **traffic generator** that keeps the dashboard visibly moving.

## 2. Terms

| Term | Meaning |
|---|---|
| Tenant | An API client, identified by `tenantId`. Has a name and a tier. |
| Tier | Bronze, Silver or Gold. Each tier defines a request limit per period. |
| Period | The fixed quota window, the same for all tenants and aligned to UTC epoch time. Production: 1 day (resets at UTC midnight). Prototype: 1 minute. |
| Used | Allowed requests of a tenant in the current period, as counted in Redis. Only allowed requests increase it. It can end up slightly above the limit (see FR-6). |
| Rejected | Requests of a tenant rejected with 429 in the current period. |
| Near limit | `used ≥ 80% of limit` and `used < limit`. |
| Over limit | `used ≥ limit`, as reported by Redis. |
| Blocked set | Per gateway instance, in memory: the tenants known to be over limit in the current period. The quota decision only consults this set. |

## 3. Functional requirements

### 3.1 Gateway: quota enforcement

- **FR-1** The gateway exposes a business endpoint `GET /api/v1/resource` that returns a fixed mock JSON payload. It stands in for the real upstream.
- **FR-2** Every business request carries an `X-Tenant-Id` header, resolved by an upstream layer. A missing header, or an ID not in the registry, returns 400. This is a defensive guard, not a feature.
- **FR-3** Quota uses a **fixed window**. Each tenant's limit per period is defined by its tier. Counters belong to the current period; the next period starts from zero for every tenant at the same moment.
- **FR-4** The quota decision is made **in memory, with no I/O**: if the tenant is not in the blocked set, the request is allowed and served with 200.
- **FR-5** If the tenant is in the blocked set, the request is rejected with **429 Too Many Requests**, a `Retry-After` header (seconds until the next period boundary, minimum 1) and a minimal JSON error body. Rejected requests do not consume quota.
- **FR-6** Usage is recorded **asynchronously, one Redis call per request**. The request thread enqueues an event (tenant, allowed/rejected, period, second) and returns. A background writer applies each event to Redis: an allowed event increments `used`, a rejected event increments `rejected`. For allowed events Redis returns `used`; if `used ≥ limit` (the tenant's current limit), the tenant is added to the blocked set. A tenant can therefore overshoot its limit by the requests that arrive between crossing it and the Redis response. This bounded overshoot is accepted.
- **FR-6a** The blocked set belongs to one period and is discarded when the period changes, so all tenants start the new period unblocked. Each event carries the period and second of its request, so an event processed after the boundary still counts toward the period it belongs to.
- **FR-6b** The event queue is bounded. When it is full, new events are dropped and counted in a logged dropped-events metric. Request threads never block on recording.
- **FR-7** Responses carry no usage or remaining-quota headers.
- **FR-8** **Redis failure:** the gateway keeps working with what it has in memory. Tenants already in the blocked set stay blocked until the period ends; no new tenants get blocked; nothing is counted (events fail or are dropped); failures are logged. The request path is not affected.

### 3.2 Gateway: tenants and tiers

- **FR-9** The tenant registry is seeded at startup from `tenants.json` (id, name, tier) and kept in gateway memory.
- **FR-10** Tier definitions (limit per period), period length and the near-limit threshold are static configuration.
- **FR-11** An operator can change a tenant's tier. The tenant is removed from the gateway's blocked set, and its next request's event is evaluated in Redis against the new limit and the current period's existing counters, which blocks it again if it is still over. Counters are not reset or modified. Tier changes work while Redis is down. They are not persisted: a restart restores `tenants.json`.

### 3.3 Gateway: metrics and admin API

- **FR-12** For every request event, the background writer records in Redis, in the same atomic operation as the tenant's counter update: global allowed and rejected counts per second, global allowed and rejected totals for the current period, and the tenant's membership in the current period's near-limit and over-limit sets.
- **FR-13** `GET /admin/snapshot` returns:
  - the current period: index, end time, seconds left;
  - allowed and rejected requests per second for the last 60 complete seconds;
  - allowed and rejected totals for the current period;
  - the number of tenants near and over their limit;
  - one page of tenant rows: id, name, tier, limit, used, rejected, status (ok / near / over), plus the total number of matching tenants, the page number and the page size.
- **FR-14** Tenant rows support combinable filters and pagination:
  - **name:** contains, case-insensitive;
  - **id:** exact match;
  - **tier:** Bronze, Silver or Gold;
  - **status:** ok, near or over, taken from the current period's near/over sets ("ok" = all tenants not in either set);
  - **pagination:** offset-based (`page`, `size`, default size 10), sorted by name.
- **FR-15** If metrics cannot be read, the snapshot still returns the tenant rows (id, name, tier, limit) for the name, id and tier filters, and marks the metric parts as unavailable. The status filter is unavailable in that case: a request with a status filter returns the rows as unavailable.
- **FR-16** `GET /admin/tiers` lists tiers and their limits. `PUT /admin/tenants/{id}/tier` changes a tenant's tier (404 for an unknown tenant, 400 for an unknown tier).
- **FR-17** `GET /actuator/health` reports the gateway status and Redis status as a component.
- **FR-18** Admin and health endpoints are not subject to quota.

### 3.4 Admin dashboard

- **FR-19** The dashboard polls the snapshot every 1 s and health every 5 s. Polls of the same kind never overlap.
- **FR-20** The dashboard shows:
  - **Header:** gateway connection status, quota enforcement status (OK / degraded), countdown to the next reset.
  - **Traffic:** allowed and rejected req/s for the last complete second, and a 60-second trend.
  - **Period totals:** allowed and rejected requests; number of tenants near and over their limit.
  - **Tenant grid:** one page of 10 rows with filters (name, id, tier, status) and page navigation; used / limit with percentage, rejected, status, and a tier selector. The current page and filters are kept across polls.
- **FR-21** The dashboard handles the unhappy states in the table below.
- **FR-22** The screen is readable without a component library or design system.

| State | Trigger | Dashboard shows |
|---|---|---|
| Initial loading | First snapshot not yet received | A loading indicator instead of the panels; no zeros pretending to be data |
| Gateway unreachable | 2 consecutive failed snapshot polls (network error, 2 s timeout, or 5xx) | Red "Gateway unreachable" banner; last known data greyed out with "last updated N s ago"; tier selectors disabled; polling continues |
| Recovered | A successful poll after "unreachable" | Banner removed, a brief "Reconnected" notice, data refreshed, controls enabled |
| Enforcement degraded | Health reports Redis DOWN | Yellow "Quota enforcement degraded: no new blocks, counting paused" banner |
| Metrics unavailable | Snapshot marks metrics unavailable | "Metrics unavailable" in traffic and totals panels; grid shows names and tiers without usage; status filter disabled; tier changes still enabled |
| Tier change failed | PUT returns an error or times out | Inline error on the row; selector reverts to the actual tier |

### 3.5 Traffic generator

- **FR-23** The generator runs as its own container, starts with the stack, and sends continuous traffic to `GET /api/v1/resource` with `X-Tenant-Id`.
- **FR-24** It reads tenants from the shared `tenants.json`. The traffic shape (total rate, per-tenant weights) lives in its own config.
- **FR-25** The default config produces, within each period, tenants in all three states: ok, near and over.
- **FR-26** It survives gateway unavailability: it keeps sending without crashing and logs a periodic summary (sent, 200, 429, errors).

### 3.6 Running

- **FR-27** One command (a start script around docker compose) builds and starts Redis, the gateway, the dashboard and the generator.
- **FR-28** Scripts in `/scripts` start and stop Redis, the gateway and the generator individually, and stop the whole stack. The dashboard has no component scripts: it observes the Redis and gateway outages, so it starts and stops only with the whole stack.

## 4. Non-functional requirements

### 4.1 Production design inputs (inform the design, not load-tested in the prototype)

- **NFR-1 Load:** about 250,000 req/min at peak ≈ 4,200 req/s; about 100,000 req/min off-peak ≈ 1,700 req/s; about 50,000 tenants.
- **NFR-2 Per-request cost:** the quota decision is an in-memory set lookup with no I/O; Redis is off the request path. Recording costs one asynchronous Redis call per request.
- **NFR-3 Dashboard cost:** Redis reads per snapshot do not depend on the total number of tenants or the number of open dashboards. The global part is computed once per second; a page of rows is limited to its page size (10), and the status filter reads one set.
- **NFR-4 Availability:** the API keeps serving when Redis is down, enforcing from the in-memory blocked set. The gateway's liveness and readiness must not depend on Redis.
- **NFR-5 Precision:** overshoot per tenant is bounded by roughly `request rate × async recording delay`, per gateway instance. With several instances, each blocks a tenant on its own next Redis response, since the Redis count is global.
- **NFR-6 Freshness:** while healthy, dashboard data is at most about 2 s old.

### 4.2 Prototype

- **NFR-7 Laptop-friendly:** 15 seeded tenants; the generator's default rate is a few hundred req/s.
- **NFR-8 Configurable:** period length, tier limits, near threshold and generator traffic shape change without code changes.
- **NFR-9 Tested:** gateway with JUnit + Testcontainers against a real Redis (including blocking after the limit, bounded overshoot, period rollover and Redis failure); dashboard with Vitest + React Testing Library (including unhappy states); generator with Vitest.

## 5. Main scenarios

**S1. Allowed request.** The generator sends a request for a tenant not in the blocked set → 200 with the mock payload immediately → the background writer increments the tenant's `used` in Redis; traffic and usage numbers on the dashboard grow.

**S2. Quota exhausted.** A recorded request brings the tenant's `used` to the limit → Redis reports it, and the gateway adds the tenant to the blocked set; a few in-flight requests may still pass → further requests get 429 with `Retry-After`; the tenant's rejected count and the global rejected rate grow; the dashboard shows the tenant as over limit.

**S3. Period reset.** The period boundary passes → the blocked set is discarded and new counters start from zero → blocked tenants are allowed again; the dashboard's countdown restarts and near/over counts drop.

**S4. Operator monitors.** The operator opens the dashboard → sees current req/s (allowed and rejected), the 60-second trend, period totals, how many tenants are near or over, and 10 tenants with usage.

**S5. Operator finds tenants.** The operator filters by name, id, tier or status (e.g. "over" to see everyone blocked) → the grid shows the first page of matches with live usage and the total count; the operator pages through the results while the data keeps refreshing.

**S6. Operator changes a tier.** The operator picks a new tier for a tenant → the gateway updates its registry and removes the tenant from the blocked set → the tenant's next request is served, and its event is evaluated in Redis against the new limit and the existing counters, re-blocking the tenant if it is still over.

**S7. Redis down.** Redis stops → the API keeps serving; tenants already blocked stay blocked until the period ends, no new tenants get blocked, nothing is counted → the dashboard shows the degraded banner and "Metrics unavailable"; tier changes still work. Redis returns → counting resumes and the banners clear.

**S8. Gateway down.** The gateway stops → after 2 failed polls the dashboard shows "Gateway unreachable" with greyed last known data; the generator keeps retrying. The gateway returns → the dashboard shows "Reconnected" and live data.

**S9. First load.** The dashboard opens before the first snapshot arrives → a loading indicator, then live data.

## 6. Out of scope

- Authentication and authorization: for API clients (the tenant ID arrives already resolved from an upstream layer) and for the admin dashboard.
- Proxying to a real upstream; the gateway returns a stub response.
- A separate admin service, and separate Redis monitoring (production direction in architecture.md).
- A database or message broker; persisting tier changes across restarts.
- Multiple gateway instances; Redis clustering or high availability.
- Specified usage semantics on tier change, such as upgrade-resume and downgrade-over-limit display. **Preferable enhancement.**
- Editing tier limits; an audit log of tier changes.
- Top-N tenants sorted by usage.
- Push delivery to the dashboard (SSE or WebSocket); polling is used.
- Controlling the generator from the dashboard.
- Proving the production load of about 4,200 req/s; no load testing at that rate.
- Visual design, a component library or a design system.

## 7. Assumptions and accepted limitations

- A single gateway instance; the tenant registry lives in its memory.
- The quota period is 1 minute in the prototype, configurable, aligned to UTC epoch time and shared by all tenants (production: 1 day).
- Tier limits are configured per period; prototype values are tuned so that the generator's default rate produces all three states.
- The `X-Tenant-Id` header is trusted.
- When Redis is unavailable the gateway works with its in-memory blocked set; nothing is counted during the outage, and the dashboard shows metrics as unavailable.
- Bounded overshoot is accepted: a tenant may get a few requests above its limit before it is blocked.
- After a gateway restart the blocked set is empty, so each blocked tenant gets a few extra requests until its next Redis response re-blocks it. Accepted; no warm-up.
- Redis health is part of the gateway's health endpoint only as a prototype simplification. In production Redis is monitored separately.
- After a tier change, near/over set membership is updated only on the tenant's next request. A tenant upgraded while blocked stays in the "over" set until it calls again or the period ends, so the status filter can briefly show it under the old status.

### Prototype configuration (starting values, tuned in the backlog)

| Setting | Value |
|---|---|
| Period | 60 s |
| Bronze / Silver / Gold limit per period | 100 / 1,000 / 10,000 |
| Near-limit threshold | 80% |
| Seeded tenants | 15 (8 Bronze, 5 Silver, 2 Gold) |
| Generator default rate | about 200 req/s |
