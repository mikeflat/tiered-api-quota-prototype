# Spec Delta

## MODIFIED Requirements

### Requirement: Admitted business requests produce a usage event
For every business request that passes the tenant guard, the gateway SHALL create one usage event holding the tenant id, the outcome, the period index `p = floor(epochSeconds / periodSeconds)` and the epoch second, all taken from the clock when the request arrives (FR-6, FR-6a). The outcome SHALL be allowed when the request is served and rejected when it is answered `429` because the tenant is blocked. Requests rejected by the tenant guard SHALL produce no event.

#### Scenario: Allowed event for a known tenant
- **WHEN** tenant `1` sends `GET /api/v1/resource` at epoch second `1800000030` with a 60 s period
- **THEN** one allowed event is recorded for tenant `1` with period index `30000000` and second `1800000030`

#### Scenario: Period index on the boundary
- **WHEN** a request arrives at epoch second `1800000060` with a 60 s period
- **THEN** its event has period index `30000001`

#### Scenario: Rejected event for a blocked tenant
- **WHEN** tenant `1` is blocked and sends `GET /api/v1/resource` at epoch second `1800000030` with a 60 s period
- **THEN** the response is `429` and one rejected event is recorded for tenant `1` with period index `30000000` and second `1800000030`

#### Scenario: Guard rejection is not counted
- **WHEN** a client sends `GET /api/v1/resource` with `X-Tenant-Id: 999` and no tenant `999` exists
- **THEN** the response is `400` and no usage event is created
