# Proposal

## Why

Backlog item 3, `stub-business-endpoint` (docs/04 backlog.md). The gateway has a tenant registry and quota settings but no business traffic surface: `GET /api/v1/resource` returns `404`, so the generator (item 11) has nothing to call and the quota enforcement of items 4–5 has no request path to sit on. This change adds the stubbed upstream endpoint and the `X-Tenant-Id` guard in front of it, which is the point where every later per-request step (blocked-set check, usage event) plugs in.

## What Changes

- Add `GET /api/v1/resource`, returning `200` with a fixed mock JSON payload that stands in for the real upstream (FR-1, D1). The payload is the same for every tenant and carries no usage or remaining-quota headers (FR-7).
- Add a guard on every `/api/**` request that requires an `X-Tenant-Id` header naming a tenant in the in-memory registry; a missing, malformed or unknown id is answered with `400` and an `application/problem+json` body before the request reaches any controller (FR-2, D3). The check is an in-memory registry lookup with no I/O (FR-4, NFR-2).
- The guard is the architecture's `QuotaFilter` (docs/03 architecture.md §2.1), introduced here with only the tenant check; items 4–5 extend the same filter with the usage event and the blocked-set check, so no rename or second filter is needed later.
- Admin (`/admin/**`, not yet present) and actuator paths are not guarded and need no tenant header (FR-18).
- Describe the endpoint in the OpenAPI contract `gateway/adapters/api/src/main/resources/openapi/gateway-api.yaml`; the controller implements the generated interface.
- Update `gateway/README.md`, which currently says no business endpoint exists and every other path returns `404`.

## Capabilities

### New Capabilities
- `business-api`: the gateway's business traffic surface under `/api/**` — the stub resource endpoint, its payload, and the `X-Tenant-Id` tenant guard that every business request passes. Later items add quota enforcement (`429`, `Retry-After`) to this capability's request path.

### Modified Capabilities
- `gateway-runtime`: the requirement "Unknown paths are not served" uses `GET /api/v1/resource` as its example of a path that returns `404`; that path now exists, so the requirement is restated for paths that remain unserved, and it notes that unknown `/api/**` paths pass the tenant guard first.

## Impact

- **Code**: `gateway/domain` (a new `AdmissionPolicy` with its `Admission` outcomes, built on `TenantRegistry.find`), `gateway/app` (a new `AdmitBusinessCall` use case), `gateway/adapters/api` (OpenAPI path and schema, `StubController`, `QuotaFilter` with its `X-Tenant-Id` header parser), `gateway/bootstrap` (`DomainConfiguration`, `AppConfiguration` and `ApiConfiguration` wiring, `HealthEndpointTest` update, new HTTP tests), `gateway/README.md`.
- **APIs**: new `GET /api/v1/resource`; new `400` response on `/api/**` for a missing or unknown tenant.
- **Dependencies**: none new. Jackson and the OpenAPI generator are already on `:adapters:api`.
- **Other components**: none. The dashboard does not call `/api/**` and nginx does not proxy it; the generator that calls it arrives with item 11. Redis is not touched.
- **Requirements covered**: FR-1, FR-2, FR-7 (no usage headers on the stub response), FR-18 (admin/health not guarded); groundwork for FR-4/FR-5 (the filter that will hold the in-memory decision). Decisions: D1 (standalone gateway with a stubbed upstream, `StubController` as the single replacement point), D3 (tenant arrives resolved in `X-Tenant-Id`, trusted).

## Out of scope

- Quota enforcement: the blocked set, `429` and `Retry-After` (item 5), usage events and Redis recording (items 4, 7). Every known tenant gets `200` in this change.
- Authentication or verification of the tenant header: the header is trusted (requirements §7, D3); no auth (requirements §6).
- Real upstream proxying (Enhancements; D1 names the replacement path).
- Admin endpoints (items 8–10) and generator traffic (item 11).
- Per-request logging of rejected tenant ids; load testing at production rate (requirements §6).
