# Spec Delta

## MODIFIED Requirements

### Requirement: Unknown paths are not served
The gateway SHALL answer any path it does not define with `404` rather than a payload. Defined paths are `/actuator/health` and its probe and component paths, `GET /api/v1/resource`, `GET /admin/tiers`, `GET /admin/snapshot` and `PUT /admin/tenants/{id}/tier`. Under `/api/` the tenant guard runs first, so an unknown path there returns `404` only for a request with a known `X-Tenant-Id` and `400` otherwise.

#### Scenario: Request to a path that does not exist yet
- **WHEN** a client sends `GET /admin/tenants`, a path the admin API does not define, to the gateway
- **THEN** the response status is `404`

#### Scenario: Unknown business path with a known tenant
- **WHEN** a client sends `GET /api/v2/resource` with the id of a known tenant in `X-Tenant-Id`
- **THEN** the response status is `404`
