# Proposal

## Why

The gateway now enforces quotas and records usage, global traffic and near/over sets in Redis, but nothing reads that data and an operator has no way to change a tenant's tier. The dashboard backlog items (12–15) need a stable admin API first. This change delivers the whole Phase 3 admin API in one step so the snapshot contract, its filters and the tier-change rules are designed together.

Backlog mapping (docs/04 backlog.md, Phase 3), combined into one change at the user's request:

| Backlog item | Suggested id | Covered here |
|---|---|---|
| 8 | `admin-tenants-and-tiers` | `GET /admin/tiers`, `PUT /admin/tenants/{id}/tier`, unblock on tier change, works with Redis down |
| 9 | `admin-snapshot` | `GET /admin/snapshot` with the cached global metrics (rebuilt once per second), first page of tenants by name with usage, metrics marked unavailable without Redis |
| 10 | `tenant-search-and-pagination` | Combinable filters (name, id, tier, status), offset pagination with a total count, status filter unavailable without Redis |

The change id is `admin-api` because no single backticked backlog name covers all three items.

## What Changes

- **Tier administration**
  - `GET /admin/tiers` lists Bronze, Silver and Gold with their configured limit per period (FR-16).
  - `PUT /admin/tenants/{id}/tier` with body `{"tier": "Gold"}` changes a tenant's tier in the in-memory registry and removes it from the blocked set, so its next request is served and its event is evaluated in Redis against the new limit and the existing counters (FR-11, FR-16, S6). Unknown tenant → `404`, unknown tier or malformed body/id → `400`. No Redis call; works while Redis is down. Not persisted (D11).
  - Blocking and tier change are made mutually consistent, so an event that was checked against the old limit cannot re-block a tenant after its tier change.
- **Admin snapshot**
  - `GET /admin/snapshot` returns the current period (index, end time, seconds left), allowed/rejected counts for the last 60 complete seconds, period totals, near/over tenant counts, and one page of tenant rows (id, name, tier, limit, used, rejected, status) with total, page and size (FR-13).
  - The global part is read from Redis by a background job once per second in one pipelined round trip and cached; a snapshot request never reads it from Redis (NFR-3, D8).
  - Rows cost one `MGET` of `used` and `rejected` for the page only; status is computed against each tenant's current limit.
  - When Redis can't be read, the snapshot still returns `200` with the rows' id, name, tier and limit and marks the metric parts as unavailable (`null`) (FR-15, S7).
- **Tenant search and pagination**
  - Combinable query filters `name` (contains, case-insensitive), `id` (exact), `tier`, `status` (`ok`, `near`, `over`, from the current period's near/over sets), and offset pagination `page` (from 0) and `size` (default 10), sorted by name (FR-14, S5).
  - A status-filtered request reads only the needed set(s) with `SMEMBERS`; without Redis its rows are reported unavailable (FR-15).
  - Invalid parameters (unknown tier or status, non-numeric id, negative page, size outside 1..100) → `400` problem+json.
- **Registry and enforcement**
  - `TenantRegistry` gains a tier update and filtering; `BlockedTenants` gains removal of one tenant.
- The OpenAPI contract (`gateway-api.yaml`) gains the admin paths; the "unknown paths" rule now lists them as defined.

## Capabilities

### New Capabilities
- `tier-administration`: listing tiers with their limits and changing a tenant's tier through the admin API, including its effect on blocking and its behaviour without Redis.
- `admin-snapshot`: the admin snapshot endpoint — period info, cached global traffic metrics, near/over counts, filtered and paginated tenant rows with usage, and how unavailability of Redis is reported.

### Modified Capabilities
- `tenant-registry`: adds an in-memory tier change and combinable filtering (name contains, exact id, tier) on top of the existing name-sorted listing.
- `quota-enforcement`: adds removal from the blocked set on tier change and the rule that blocking uses the tenant's limit current at the moment of blocking.
- `gateway-runtime`: "Unknown paths are not served" now lists the admin endpoints as defined paths; its "admin path before the admin API exists" scenario is replaced.

## Impact

- **Gateway code**: `:domain` (tenant filter, tenant status, page request, traffic metrics value, registry tier update, blocked-set removal), `:app` (tier and snapshot use cases, metrics cache, read ports), `:adapters:infra` (Redis readers for global metrics, page usage and status sets), `:adapters:api` (admin controller, OpenAPI additions, `400` mapping), `:bootstrap` (wiring, one-thread scheduler for the metrics job).
- **HTTP API**: three new admin endpoints; no change to `/api/v1/resource` or health.
- **Redis**: read-only use of the existing keys of architecture §4; no new keys, no change to the record script.
- **Dashboard / generator**: untouched (dashboard consumers come with backlog items 12–15). nginx already forwards `/admin/`.
- **Docs**: `gateway/README.md` (admin API section, module table, tests).

Requirements and decisions covered: FR-11, FR-13, FR-14, FR-15, FR-16, FR-18, NFR-3, NFR-4 (admin endpoints never depend on Redis to answer), D2, D8, D11.

### Out of scope (docs/02 §6)
- Authentication or authorization of the admin API.
- Persisting tier changes across restarts; editing tier limits; an audit log of tier changes.
- Specified usage semantics on tier change (upgrade-resume, downgrade-over-limit display): rows show Redis `used` against the new limit and the near/over sets update only on the tenant's next event (docs/02 §7).
- Top-N tenants sorted by usage; sorting other than by name; cursor pagination.
- SSE/WebSocket push; a separate admin service; multiple gateway instances.
- Any dashboard UI work.
