# Design

## Context

The gateway is a six-module Gradle build (`:domain`, `:app`, `:adapters:api`, `:adapters:infra`, `:adapters:persistence`, `:bootstrap`). The registry (`TenantRegistry`, `ConcurrentHashMap<Long, Tenant>`, `find(long)`) is built at startup by the `LoadTenantRegistry` use case and exposed as a bean from `AppConfiguration`. Only `bootstrap` is component-scanned; adapter beans are declared with `@Bean` methods in `ApiConfiguration`, which is empty today. `:adapters:api` is spec-first: `gateway-api.yaml` (no paths yet) is turned into interfaces and DTOs by `openApiGenerate`, with `ProblemDetail` mapped to Spring's own class. `application.yaml` enables Spring MVC problem details. `ArchitectureTest` (ArchUnit) forbids Spring in `:domain` and `:app`. `HealthEndpointTest.unknown_api_path_is_not_found` asserts `404` on `/api/v1/resource` and must change. No Redis code exists yet; Spring Boot tests run without Redis.

Motivation and scope: see proposal.md. Behaviour: see the `business-api` and `gateway-runtime` deltas.

## Goals / Non-Goals

**Goals:**
- One request-admission path for `/api/**` — filter → use case → domain policy → registry — that items 4–5 extend (usage event, blocked set) without moving or renaming anything.
- Each concern sits in its own layer: reading the `X-Tenant-Id` text is an adapter concern, the admission rule is domain policy, and coordinating ports (clock, usage recording, from item 4 on) is the use case's job. Parser, policy and use case are plain Java, unit-tested without Spring.
- The tenant check allocates nothing beyond the header string on the success path and does no I/O.

**Non-Goals:**
- A domain-level `TenantId` value type. Tenant ids stay `long`, as in `Tenant` and `TenantRegistry`.
- A use case behind `StubController`: the payload is a constant stand-in for an upstream, not business logic (D1). The controller is the single replacement point for a proxy route.
- Content negotiation for error bodies: the guard always answers `application/problem+json`.

## Decisions

### D-1 Layer placement

| Layer (module / package) | Type | Role |
|---|---|---|
| domain `…domain.tenant` | `TenantRegistry` (existing) | `find(long)` is the only call used; no change |
| domain `com.mikeflat.gateway.domain.quota` | `AdmissionPolicy` | The business rule: `Admission admit(long tenantId)` admits a tenant only if it is in the registry. Pure Java; built from `TenantRegistry`. Item 5 adds the blocked-set check for the current period here |
| domain `…domain.quota` | `Admission` (sealed interface) with records `Admitted(Tenant tenant)`, `TenantUnknown()` | Outcome of the policy. Item 5 adds `Rejected(Tenant, long retryAfterSeconds)` here; the filter's `switch` then fails to compile until it handles it |
| app `com.mikeflat.gateway.app.quota` | `AdmitBusinessCall` (use case, inbound port) | `Admission admit(long tenantId)`. Delegates to `AdmissionPolicy` for now; items 4–5 add the clock and `UsageRecorder` ports here and hand the usage event on. Named like `LoadTenantRegistry` (verb + object) |
| adapter in `com.mikeflat.gateway.adapters.api.filter` | `TenantIdHeader` (package-private sealed interface) with records `Missing()`, `Malformed()`, `Present(long tenantId)` | `static TenantIdHeader parse(String value)`: the header text rule of D-4. HTTP input format, so it never reaches the domain |
| adapter in `…adapters.api.filter` | `QuotaFilter` (extends `OncePerRequestFilter`) | Parses `X-Tenant-Id` with `TenantIdHeader`; on `Present` calls `AdmitBusinessCall` and continues the chain on `Admitted`; otherwise writes the `400` problem body and stops. Items 4–5 add the `429` branch here |
| adapter in `…adapters.api.filter` | `ProblemResponseWriter` (package-private) | Writes `status`, `Content-Type: application/problem+json` and a JSON body built from a private record (`type`, `title`, `status`, `detail`, `instance`) with the injected Jackson `JsonMapper`. Reused by item 5 for the `429` body |
| adapter in `com.mikeflat.gateway.adapters.api.web` | `StubController` (implements generated `ResourceApi`) | Returns `200` with a constant `MockResource("mock-resource", "Served by the quota gateway stub")` |
| adapter in, generated `…adapters.api.generated.{api,model}` | `ResourceApi`, `MockResource` | Generated from `gateway-api.yaml` at build time, not committed |
| infrastructure `com.mikeflat.gateway.bootstrap.config` | `DomainConfiguration` | Adds the `AdmissionPolicy` bean from `TenantRegistry` |
| infrastructure `…bootstrap.config` | `AppConfiguration` | Adds the `AdmitBusinessCall` bean from `AdmissionPolicy` |
| infrastructure `…bootstrap.config` | `ApiConfiguration` | Adds the `StubController` bean and a `FilterRegistrationBean<QuotaFilter>` with URL pattern `/api/*`, dispatcher type `REQUEST` only |

Ports used: none new. `AdmissionPolicy` reads the in-memory `TenantRegistry` directly — the registry is the domain's request-time source of truth, not a repository (same reasoning as tenant-registry-and-config D-1). No outbound port is touched, so there is no I/O on the request path (FR-4, NFR-2).

*Alternative considered:* the filter parses the header and calls `TenantRegistry.find` itself. Rejected: the admission rule would live in a Spring class and be testable only with servlet mocks, and item 5's blocked-set and period logic would follow it there.

*Alternative considered:* one app-layer use case that parses the header and looks up the tenant (the first version of this design, `AdmitRequest`). Rejected: it mixed the HTTP input format with the business rule, left the domain rule outside the domain, and its name read like a request DTO.

### D-2 Servlet filter, not a `HandlerInterceptor`

The guard is a servlet filter registered for `/api/*`, as the architecture's `QuotaFilter`. It runs before `DispatcherServlet`, so every method and path under `/api/` — including unmapped ones — is checked first (spec: unknown business path without a tenant is `400`), and the hot path does no handler lookup for rejected requests. `/api/*` in servlet mapping matches `/api` and everything below it but not `/apix`. `OncePerRequestFilter` skips error dispatches by default, so the `404` error dispatch for an unknown `/api/` path is not re-checked.

*Alternative:* a `HandlerInterceptor` with an `@RestControllerAdvice` producing `ProblemDetail`. Rejected: interceptors only run for mapped handlers, so `GET /api/v1/other` without a tenant would be `404`, not `400`, and the architecture names a filter.

### D-3 Problem body written by the filter

A filter runs outside Spring MVC, so `@ExceptionHandler` and Boot's problem-details handler do not apply. The filter writes the body itself through `ProblemResponseWriter`: `type` `about:blank`, `title` `Bad Request`, `status` `400`, `detail` either `Missing X-Tenant-Id header` or `X-Tenant-Id does not identify a known tenant`, `instance` the request URI. A private record is serialized instead of Spring's `ProblemDetail`, because the plain `JsonMapper` does not carry the Spring MVC mixin for `ProblemDetail` and would emit an extra `properties` field. The header value is never written to the body or logs (no reflection of client input). The body has the same fields MVC produces for its own errors, so clients see one error shape.

*Alternative:* delegate to the `handlerExceptionResolver` bean from the filter. Rejected: it couples the filter to MVC internals for a two-message error, and still needs a custom exception type and advice.

### D-4 Header parsing rule

`TenantIdHeader.parse` treats the header as `Missing` when it is `null` or blank. Otherwise the value must be 1–18 ASCII digits (so `Long.parseLong` cannot overflow, and `+5`, `-1`, `1.5`, `acme` are rejected without exception handling on the hot path), giving `Present(id)`; anything else is `Malformed`. A `Present` id is then judged by `AdmissionPolicy` and must be found in the registry. The filter answers `Malformed` and `TenantUnknown` with the same detail, so the client gets one message whether the id is malformed or simply not registered. Leading zeros are accepted (`01` is tenant `1`); this is unobservable to the generator and not worth a rule. When a request carries several `X-Tenant-Id` headers, the first one is used (`getHeader`).

### D-5 OpenAPI contract

`gateway-api.yaml` gains `GET /api/v1/resource` with tag `Resource`, operationId `getResource`, a required header parameter `X-Tenant-Id` (integer, int64, minimum 1), a `200` response with schema `MockResource` (`id`, `message`, both required strings) and a `400` response with `application/problem+json` schema `ProblemDetail` (mapped to Spring's class, not generated). The header is declared so the contract documents it; by the time the controller runs, the filter has already guaranteed it, so the controller ignores the argument.

### D-6 Tests per layer

- `:domain` — `AdmissionPolicyTest`: plain JUnit, registry built in the test; registered id admitted with its tenant, unregistered id and `0` unknown.
- `:app` — `AdmitBusinessCallTest`: plain JUnit over a real `AdmissionPolicy`; known id admitted, unknown id unknown.
- `:adapters:api` — `TenantIdHeaderTest`: plain JUnit; missing (null, empty, blank), malformed (`acme`, `-1`, `+1`, `1.5`, surrounding spaces, non-ASCII digits, 19+ digits), present (including 18 digits and leading zeros).
- `:adapters:api` — `QuotaFilterTest` with `MockHttpServletRequest`/`MockFilterChain` and a real `AdmitBusinessCall` over a real `AdmissionPolicy`: chain continues for a known tenant; `400`, problem content type, `detail` and no echoed value otherwise, chain not called. `StubControllerTest` with standalone `MockMvc`: `200` and the exact JSON.
- `:bootstrap` — `BusinessEndpointTest` (`@SpringBootTest(RANDOM_PORT)`, real HTTP via `HttpProbe` extended with request headers): every `business-api` scenario over the real filter registration, including `/api/v1/other`, `/apix/resource`, health without a header and absence of rate-limit headers. `CustomTenantsFileBusinessEndpointTest` points `tenants.file` at a two-tenant temp file. `HealthEndpointTest` drops `unknown_api_path_is_not_found` and asserts `404` on `/admin/snapshot` instead.
- No Testcontainers here: nothing in this change talks to Redis, and the "Redis down" scenarios hold because the existing Boot tests already start with no Redis server.

## Risks / Trade-offs

- [The filter writes JSON with its own record, so its shape could drift from MVC's problem bodies] → `BusinessEndpointTest` asserts the fields; item 5 reuses the same writer for `429`.
- [Filter registered by URL pattern; a later `/api` path outside `/api/*` would bypass it] → all business paths live under `/api/`; the `/apix` test pins the boundary.
- [Generated controller signature changes if the spec changes] → generated code is rebuilt on every compile; `StubControllerTest` fails on a mismatch.
- [Header declared required in OpenAPI but enforced by the filter] → the filter always runs first; the duplication is documentation only.

## Migration Plan

No data or configuration changes. Rolling back the change returns `/api/v1/resource` to `404`. The generator (item 11) must not be released before this change.
