# Spec Delta

## MODIFIED Requirements

### Requirement: Unknown paths are not served
The gateway SHALL answer any path it does not define with `404` rather than a payload. Defined paths are `/actuator/health` and `GET /api/v1/resource`; admin endpoints are added by later changes. Under `/api/` the tenant guard runs first, so an unknown path there returns `404` only for a request with a known `X-Tenant-Id` and `400` otherwise.

#### Scenario: Request to a path that does not exist yet
- **WHEN** a client sends `GET /admin/snapshot` to the gateway before the admin API exists
- **THEN** the response status is `404`

#### Scenario: Unknown business path with a known tenant
- **WHEN** a client sends `GET /api/v2/resource` with the id of a known tenant in `X-Tenant-Id`
- **THEN** the response status is `404`
