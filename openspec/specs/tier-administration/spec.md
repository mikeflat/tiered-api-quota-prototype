# tier-administration Specification

## Purpose

Lets an operator see the configured tiers with their limits and change a tenant's tier at runtime through the gateway's admin API, with the change taking effect on enforcement immediately and without depending on Redis.

## Requirements

### Requirement: Tiers are listed with their limits
`GET /admin/tiers` SHALL return `200` with a JSON array holding one entry per tier, in the order Bronze, Silver, Gold, each with `name` (canonical tier name) and `limit` (requests per period from configuration) (FR-16). It SHALL make no Redis call.

#### Scenario: Default limits
- **WHEN** the gateway runs with the default quota settings and a client sends `GET /admin/tiers`
- **THEN** the response is `200` with `[{"name":"Bronze","limit":100},{"name":"Silver","limit":1000},{"name":"Gold","limit":10000}]`

#### Scenario: Configured limits
- **WHEN** the gateway runs with `QUOTA_LIMIT_SILVER=250` and a client sends `GET /admin/tiers`
- **THEN** the Silver entry has `"limit":250`

#### Scenario: Redis down
- **WHEN** Redis is unreachable and a client sends `GET /admin/tiers`
- **THEN** the response is `200` with all three tiers, without waiting for a Redis timeout

### Requirement: Operator changes a tenant's tier
`PUT /admin/tenants/{id}/tier` with JSON body `{"tier":"<name>"}` SHALL set the tenant's tier in the gateway's in-memory registry and return `200` with the tenant's `id`, `name`, `tier` and `limit` after the change (FR-11, FR-16). Tier names SHALL be accepted case-insensitively and reported by canonical name.

#### Scenario: Upgrade a tenant
- **WHEN** tenant `1` is Bronze and a client sends `PUT /admin/tenants/1/tier` with `{"tier":"Gold"}`
- **THEN** the response is `200` with `"id":1`, `"tier":"Gold"` and `"limit":10000`
- **AND** a following `GET /admin/snapshot?id=1` shows tenant `1` with tier `Gold`

#### Scenario: Lower-case tier name
- **WHEN** a client sends `PUT /admin/tenants/1/tier` with `{"tier":"silver"}`
- **THEN** the response is `200` with `"tier":"Silver"`

#### Scenario: Same tier again
- **WHEN** tenant `1` is Bronze and a client sends `PUT /admin/tenants/1/tier` with `{"tier":"Bronze"}`
- **THEN** the response is `200` with `"tier":"Bronze"`

### Requirement: Tier change is rejected for unknown tenants and invalid input
The tier-change endpoint SHALL return `404` when the id is an integer that identifies no tenant, and `400` when the id is not a positive integer, the body is missing or not JSON, or `tier` is missing or not a known tier (FR-16). Error responses SHALL be `application/problem+json`, and the registry SHALL stay unchanged.

#### Scenario: Unknown tenant
- **WHEN** a client sends `PUT /admin/tenants/99/tier` with `{"tier":"Gold"}` and no tenant has id `99`
- **THEN** the response is `404` with a problem body

#### Scenario: Unknown tier
- **WHEN** a client sends `PUT /admin/tenants/1/tier` with `{"tier":"Platinum"}`
- **THEN** the response is `400` with a problem body
- **AND** tenant `1` keeps its tier

#### Scenario: Missing tier field
- **WHEN** a client sends `PUT /admin/tenants/1/tier` with `{}`
- **THEN** the response is `400`

#### Scenario: Malformed body
- **WHEN** a client sends `PUT /admin/tenants/1/tier` with the body `{"tier":`
- **THEN** the response is `400`

#### Scenario: Non-numeric id
- **WHEN** a client sends `PUT /admin/tenants/acme/tier` with `{"tier":"Gold"}`
- **THEN** the response is `400`

### Requirement: Tier change applies to the next request and the next recorded event
After a tier change the tenant's next request SHALL be admitted unless it is blocked again later, and the next usage event of the tenant applied by the writer SHALL be evaluated against the new tier's limit and the current period's existing counters, which SHALL NOT be reset or modified by the change (FR-11, S6).

#### Scenario: Blocked tenant upgraded
- **WHEN** the Bronze limit is `5`, Bronze tenant `1` is blocked with `used` `5`, and its tier is changed to Silver (limit `50`)
- **THEN** tenant `1`'s next request gets `200`
- **AND** after its event is applied its `used` is `6` and it is not blocked

#### Scenario: Blocked tenant moved to a tier it still exceeds
- **WHEN** the Bronze limit is `5`, the Silver limit is `50`, Silver tenant `9` is blocked with `used` `50`, and its tier is changed to Bronze
- **THEN** tenant `9`'s next request gets `200`
- **AND** after its event is applied with `used` `51` it is blocked again and following requests get `429`

#### Scenario: Counters untouched
- **WHEN** tenant `1` has `used` `40` and `rejected` `3` in the current period and its tier is changed
- **THEN** `quota:used:1:{p}` is still `40` and `quota:rejected:1:{p}` is still `3` until the tenant's next event

### Requirement: Tier change works without Redis
A tier change SHALL make no Redis call and SHALL succeed while Redis is unreachable, including removing the tenant from the blocked set (FR-11, D6, S7).

#### Scenario: Redis down
- **WHEN** Redis is unreachable, Bronze tenant `1` is blocked, and a client sends `PUT /admin/tenants/1/tier` with `{"tier":"Gold"}`
- **THEN** the response is `200` without waiting for a Redis timeout
- **AND** tenant `1`'s next request gets `200`

### Requirement: Tier changes are not persisted
Tier changes SHALL live only in gateway memory; the tenants file SHALL NOT be written, and a gateway restart SHALL restore the tiers from the tenants file (FR-11, D11).

#### Scenario: Restart restores the seed tier
- **WHEN** Bronze tenant `1` is changed to Gold and the gateway restarts
- **THEN** tenant `1` is Bronze again and the tenants file is unchanged
