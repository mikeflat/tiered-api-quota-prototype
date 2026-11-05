# business-api Specification

## Purpose

Defines the gateway's business traffic surface under `/api/**`: the stubbed upstream resource and the tenant guard every business request passes, so the generator and later quota enforcement have one stable request path.

## Requirements

### Requirement: Stub resource returns a fixed mock payload
The gateway SHALL answer `GET /api/v1/resource` from a known tenant with `200`, `Content-Type: application/json` and the fixed body `{"id":"mock-resource","message":"Served by the quota gateway stub"}`. The body SHALL be identical for every tenant and every request (FR-1, D1).

#### Scenario: Known tenant gets the mock payload
- **WHEN** a client sends `GET /api/v1/resource` with `X-Tenant-Id: 1` and tenant `1` is in the registry
- **THEN** the response status is `200`
- **AND** the `Content-Type` is `application/json`
- **AND** the body is `{"id":"mock-resource","message":"Served by the quota gateway stub"}`

#### Scenario: Payload does not depend on the tenant
- **WHEN** two known tenants of different tiers each send `GET /api/v1/resource`
- **THEN** both responses have status `200` and identical bodies

#### Scenario: Works without Redis
- **WHEN** no Redis server is available and a known tenant sends `GET /api/v1/resource`
- **THEN** the response status is `200` with the mock payload

### Requirement: Business responses carry no quota headers
Responses from `/api/**` SHALL NOT carry usage, limit or remaining-quota headers (FR-7).

#### Scenario: No rate-limit headers on success
- **WHEN** a known tenant sends `GET /api/v1/resource`
- **THEN** the response has no header whose name starts with `X-RateLimit` or `RateLimit`, and no `X-Quota` header

### Requirement: Business requests require a known tenant
Every request to a path under `/api/` SHALL carry an `X-Tenant-Id` header whose value is the decimal id of a tenant in the in-memory registry. Otherwise the gateway SHALL answer `400` before any handler runs, for every method and path under `/api/`, including paths that do not exist (FR-2, D3).

#### Scenario: Header missing
- **WHEN** a client sends `GET /api/v1/resource` without `X-Tenant-Id`
- **THEN** the response status is `400`

#### Scenario: Header empty
- **WHEN** a client sends `GET /api/v1/resource` with an empty `X-Tenant-Id`
- **THEN** the response status is `400`

#### Scenario: Unknown tenant id
- **WHEN** a client sends `GET /api/v1/resource` with `X-Tenant-Id: 999` and no tenant `999` exists
- **THEN** the response status is `400`

#### Scenario: Id that is not a positive whole number
- **WHEN** a client sends `GET /api/v1/resource` with `X-Tenant-Id` set to `acme`, `0`, `-1`, `1.5` or `99999999999999999999`
- **THEN** the response status is `400`

#### Scenario: Unknown business path without a tenant
- **WHEN** a client sends `GET /api/v1/other` without `X-Tenant-Id`
- **THEN** the response status is `400`

#### Scenario: Unknown business path with a known tenant
- **WHEN** a client sends `GET /api/v1/other` with the id of a known tenant
- **THEN** the response status is `404`

#### Scenario: Registry reflects the loaded tenants file
- **WHEN** the gateway was started with a tenants file holding only tenants `1` and `2`
- **THEN** `X-Tenant-Id: 2` gets `200` and `X-Tenant-Id: 3` gets `400`

### Requirement: Tenant guard rejection has a problem body
A `400` from the tenant guard SHALL have `Content-Type: application/problem+json` and a body with `status` `400`, `title` `Bad Request` and a `detail` stating whether the header was missing or did not identify a known tenant. The body SHALL NOT echo the header value.

#### Scenario: Missing header detail
- **WHEN** a client sends `GET /api/v1/resource` without `X-Tenant-Id`
- **THEN** the `Content-Type` is `application/problem+json`
- **AND** the body has `"status":400` and a `detail` naming the missing `X-Tenant-Id` header

#### Scenario: Unknown tenant detail
- **WHEN** a client sends `GET /api/v1/resource` with `X-Tenant-Id: <script>`
- **THEN** the `Content-Type` is `application/problem+json`
- **AND** the body has `"status":400` and a `detail` stating that `X-Tenant-Id` does not identify a known tenant
- **AND** the body does not contain `<script>`

### Requirement: Tenant guard performs no I/O
Deciding whether a business request passes the tenant guard SHALL use only gateway memory: no Redis call, file read or other I/O on the request path (FR-4, NFR-2).

#### Scenario: Guard keeps working with Redis down
- **WHEN** no Redis server is available
- **THEN** a known tenant gets `200` and an unknown tenant gets `400` on `GET /api/v1/resource`

### Requirement: Non-business paths are not guarded
Paths outside `/api/`, such as `/actuator/health` and the admin API, SHALL NOT require `X-Tenant-Id` (FR-18).

#### Scenario: Health without a tenant header
- **WHEN** a client sends `GET /actuator/health` without `X-Tenant-Id`
- **THEN** the response status is `200`

#### Scenario: Path that only shares the prefix
- **WHEN** a client sends `GET /apix/resource` without `X-Tenant-Id`
- **THEN** the response status is `404`
