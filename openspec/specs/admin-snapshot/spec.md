# admin-snapshot Specification

## Purpose

Gives the admin dashboard one polled endpoint with the current quota period, global traffic metrics, near/over counts and one filtered, paginated page of tenants with their usage, at a Redis cost independent of the tenant count and the number of viewers, and still useful while Redis is unavailable.

## Requirements

### Requirement: Snapshot response shape
`GET /admin/snapshot` SHALL return `200` with JSON holding `period`, `metrics` and `tenants` (FR-13). It SHALL NOT require `X-Tenant-Id` and SHALL NOT be subject to quota (FR-18).

#### Scenario: Healthy snapshot
- **WHEN** Redis is reachable and a client sends `GET /admin/snapshot` without `X-Tenant-Id`
- **THEN** the response is `200` with `application/json`
- **AND** the body has objects `period`, `metrics` and `tenants`

#### Scenario: Snapshot is not counted
- **WHEN** a client sends `GET /admin/snapshot` 500 times within one period
- **THEN** every response is `200` and no `quota:*` or `traffic:*` key is incremented by those requests

### Requirement: Snapshot reports the current period
`period` SHALL hold `index` (the current period index), `endsAt` (the UTC instant of the next period boundary, ISO-8601) and `secondsLeft` (whole seconds until that boundary, at least 1), computed from the gateway clock at request time without Redis (FR-13).

#### Scenario: Middle of a period
- **WHEN** the period is `60` s and the gateway clock reads epoch second `6_000_045`
- **THEN** `period.index` is `100000`, `period.endsAt` is the instant of epoch second `6_000_060`, and `period.secondsLeft` is `15`

#### Scenario: Redis down
- **WHEN** Redis is unreachable
- **THEN** `period` is still present with the same fields

### Requirement: Snapshot reports global traffic metrics
`metrics` SHALL hold `perSecond` (60 entries, oldest first, each `second`, `allowed`, `rejected`, covering the 60 complete seconds before the metrics were read, with `0` for seconds without traffic), `totals` (`allowed`, `rejected` of the current period), `tenantsNear` and `tenantsOver` (sizes of the current period's near and over sets) (FR-12, FR-13).

#### Scenario: Traffic in the last minute
- **WHEN** tenants sent `3` allowed requests in epoch second `s` and `1` rejected request in `s`, and the metrics were read during second `s + 1`
- **THEN** the last `perSecond` entry has `second` `s`, `allowed` `3` and `rejected` `1`
- **AND** `perSecond` has 60 entries for seconds `s - 59` to `s`

#### Scenario: Quiet seconds
- **WHEN** no request arrived in second `s - 10`
- **THEN** the `perSecond` entry for `s - 10` has `allowed` `0` and `rejected` `0`

#### Scenario: Period totals and set sizes
- **WHEN** in the current period `traffic:total:allowed:{p}` is `120`, `traffic:total:rejected:{p}` is `7`, two tenants are in `quota:near:{p}` and one in `quota:over:{p}`
- **THEN** `metrics.totals` is `{"allowed":120,"rejected":7}`, `metrics.tenantsNear` is `2` and `metrics.tenantsOver` is `1`

#### Scenario: New period
- **WHEN** a new period has just started and no event of it has been recorded
- **THEN** `metrics.totals` has `allowed` `0` and `rejected` `0`, and `tenantsNear` and `tenantsOver` are `0`

### Requirement: Global metrics are read once per second regardless of viewers
The gateway SHALL read the global metrics from Redis in the background about once per second in one round trip and serve them from memory; snapshot requests SHALL NOT read global metrics from Redis, so their Redis cost does not grow with the number of tenants or open dashboards (NFR-3, D8, NFR-6).

#### Scenario: Many viewers
- **WHEN** 20 clients each send `GET /admin/snapshot` within the same second without filters
- **THEN** the global metrics are read from Redis at most twice in that second

#### Scenario: Metrics follow new traffic
- **WHEN** Redis is reachable and new events have been recorded
- **THEN** within 2 seconds a snapshot's `metrics` include them

### Requirement: Metrics are marked unavailable when Redis can't be read
When the last background read failed, or no read succeeded within the last 3 seconds, `metrics` SHALL be `null` while `period` and `tenants` are still returned with status `200` (FR-15, S7). Metrics SHALL become available again after a successful read without a restart.

#### Scenario: Redis down
- **WHEN** Redis becomes unreachable and a client sends `GET /admin/snapshot` 3 seconds later
- **THEN** the response is `200` with `"metrics":null`

#### Scenario: Redis hangs
- **WHEN** Redis accepts connections but does not answer and a client sends `GET /admin/snapshot`
- **THEN** the response arrives within 2 seconds

#### Scenario: Redis returns
- **WHEN** Redis was unreachable and becomes reachable again
- **THEN** within 3 seconds a snapshot has non-null `metrics` again

### Requirement: Tenant rows carry identity, limit and usage
`tenants.rows` SHALL hold one entry per tenant on the requested page with `id`, `name`, `tier` (canonical name), `limit` (of the current tier), `used` and `rejected` (the tenant's current-period counters, `0` when absent) and `status` (`over` when `used ≥ limit`, `near` when `used` is at least the near boundary, else `ok`, against the current limit) (FR-13).

#### Scenario: Tenant with usage
- **WHEN** Bronze limit is `100`, near threshold `80` %, and Bronze tenant `1` has `used` `85` and `rejected` `0`
- **THEN** its row is `{"id":1,"name":"Acme Corp","tier":"Bronze","limit":100,"used":85,"rejected":0,"status":"near"}`

#### Scenario: Tenant without usage this period
- **WHEN** tenant `2` sent no request in the current period
- **THEN** its row has `used` `0`, `rejected` `0` and `status` `ok`

#### Scenario: Status after a tier change
- **WHEN** tenant `1` has `used` `100`, was Bronze (limit `100`) and is changed to Silver (limit `1000`)
- **THEN** its row shows `tier` `Silver`, `limit` `1000`, `used` `100` and `status` `ok`

### Requirement: Row usage costs one read for the page only
For each snapshot request the gateway SHALL read `used` and `rejected` only for the tenants on the returned page, in one Redis round trip, plus at most one round trip for a status filter (NFR-3, architecture §6). A page with no rows SHALL need no usage read.

#### Scenario: Default page
- **WHEN** a client sends `GET /admin/snapshot` with 15 tenants registered
- **THEN** usage is read for the 10 tenants on page `0` only, in one round trip

### Requirement: Rows without usage while Redis is down
When the usage of the page can't be read and no status filter is given, the snapshot SHALL still return the page's rows with `id`, `name`, `tier` and `limit`, with `used`, `rejected` and `status` set to `null`, and the correct `total`, `page` and `size` (FR-15).

#### Scenario: Redis down, no status filter
- **WHEN** Redis is unreachable and a client sends `GET /admin/snapshot?tier=Gold`
- **THEN** the response is `200` with `tenants.total` `2` and two rows with names, tier `Gold` and limit, and `"used":null`, `"rejected":null`, `"status":null`

### Requirement: Tenant rows are filtered
The snapshot SHALL accept the combinable query parameters `name` (contains, case-insensitive), `id` (exact tenant id), `tier` (`Bronze`, `Silver`, `Gold`, case-insensitive) and `status` (`ok`, `near`, `over`) (FR-14). Combined filters SHALL all apply; an empty value SHALL mean no filter.

#### Scenario: Name contains
- **WHEN** a client sends `GET /admin/snapshot?name=BANK`
- **THEN** `tenants.rows` holds only tenants whose name contains `bank` in any letter case, e.g. `Meridian Bank`

#### Scenario: Exact id
- **WHEN** a client sends `GET /admin/snapshot?id=1`
- **THEN** `tenants.rows` holds only tenant `1` and `tenants.total` is `1`

#### Scenario: Id that matches no tenant
- **WHEN** a client sends `GET /admin/snapshot?id=99`
- **THEN** the response is `200` with `tenants.rows` empty and `tenants.total` `0`

#### Scenario: Tier
- **WHEN** a client sends `GET /admin/snapshot?tier=silver` with the seed tenants
- **THEN** `tenants.total` is `5` and every row has tier `Silver`

#### Scenario: Combined filters
- **WHEN** a client sends `GET /admin/snapshot?tier=Bronze&name=co`
- **THEN** every row is Bronze and has `co` in its name, e.g. `Acme Corp` and `Cobalt Labs`

#### Scenario: Empty value
- **WHEN** a client sends `GET /admin/snapshot?name=&tier=`
- **THEN** the result is the same as without those parameters

### Requirement: Status filter uses the current period's near and over sets
With `status=near` or `status=over` the rows SHALL be the filtered tenants that are members of the current period's near or over set; with `status=ok` they SHALL be the filtered tenants in neither set (FR-14, D8). A row's `status` SHALL still be computed from its `used` against its current limit (docs/02 §7).

#### Scenario: Over
- **WHEN** tenants `1` and `3` are in `quota:over:{p}` and a client sends `GET /admin/snapshot?status=over`
- **THEN** `tenants.total` is `2` and the rows are tenants `1` and `3`, sorted by name

#### Scenario: Ok
- **WHEN** with the 15 seed tenants, two are in `quota:near:{p}` and one in `quota:over:{p}`, and a client sends `GET /admin/snapshot?status=ok`
- **THEN** `tenants.total` is `12` and no row is one of those three tenants

#### Scenario: Status combined with tier
- **WHEN** Bronze tenant `1` and Silver tenant `9` are in `quota:over:{p}` and a client sends `GET /admin/snapshot?status=over&tier=Bronze`
- **THEN** the only row is tenant `1`

#### Scenario: Upgraded tenant still in the over set
- **WHEN** tenant `1` is in `quota:over:{p}`, its tier is raised so its `used` is below the new near boundary, and it has sent no request since
- **THEN** `GET /admin/snapshot?status=over` still lists tenant `1`, with `status` `ok` in its row

### Requirement: Status filter is unavailable without Redis
When a `status` filter is given and the set or usage reads fail, the snapshot SHALL return `200` with `tenants.rows` and `tenants.total` set to `null` and `tenants.rowsAvailable` `false`; `period` SHALL still be present (FR-15). In every other response `tenants.rowsAvailable` SHALL be `true`.

#### Scenario: Redis down with a status filter
- **WHEN** Redis is unreachable and a client sends `GET /admin/snapshot?status=over`
- **THEN** the response is `200` with `"rowsAvailable":false`, `"rows":null` and `"total":null`

#### Scenario: Redis down without a status filter
- **WHEN** Redis is unreachable and a client sends `GET /admin/snapshot?name=a`
- **THEN** `tenants.rowsAvailable` is `true` and `tenants.rows` lists the matching tenants

### Requirement: Tenant rows are paginated by offset and sorted by name
Rows SHALL be sorted by name (case-insensitive, ties by id) and cut by `page` (from `0`, default `0`) and `size` (`1`..`100`, default `10`) at offset `page × size`. `tenants` SHALL report `total` (all matching tenants), `page` and `size` (FR-14, FR-13).

#### Scenario: Default first page
- **WHEN** a client sends `GET /admin/snapshot` with the 15 seed tenants
- **THEN** `tenants.rows` holds the first 10 tenants by name, `tenants.total` is `15`, `tenants.page` is `0` and `tenants.size` is `10`

#### Scenario: Second page
- **WHEN** a client sends `GET /admin/snapshot?page=1`
- **THEN** `tenants.rows` holds the remaining 5 tenants by name and `tenants.total` is `15`

#### Scenario: Page past the end
- **WHEN** a client sends `GET /admin/snapshot?page=5`
- **THEN** the response is `200` with `tenants.rows` empty, `tenants.total` `15` and `tenants.page` `5`

#### Scenario: Custom size
- **WHEN** a client sends `GET /admin/snapshot?size=3&page=1`
- **THEN** `tenants.rows` holds the 4th to 6th tenants by name and `tenants.size` is `3`

### Requirement: Invalid snapshot parameters are rejected
The snapshot SHALL return `400` with an `application/problem+json` body when `tier` or `status` is not a known value, `id` is not a positive integer, `page` is negative or not an integer, or `size` is outside `1`..`100` or not an integer. Unknown query parameters SHALL be ignored.

#### Scenario: Unknown tier
- **WHEN** a client sends `GET /admin/snapshot?tier=Platinum`
- **THEN** the response is `400` with a problem body

#### Scenario: Unknown status
- **WHEN** a client sends `GET /admin/snapshot?status=blocked`
- **THEN** the response is `400`

#### Scenario: Bad paging
- **WHEN** a client sends `GET /admin/snapshot?page=-1`, `?size=0`, `?size=101` or `?page=x`
- **THEN** each response is `400`

#### Scenario: Bad id
- **WHEN** a client sends `GET /admin/snapshot?id=acme` or `?id=0`
- **THEN** the response is `400`

#### Scenario: Unknown parameter
- **WHEN** a client sends `GET /admin/snapshot?sort=used`
- **THEN** the response is `200` and rows are sorted by name
