# Tasks

## 1. Domain policy and application use case

- [x] 1.1 Add the sealed `Admission` type with `Admitted(Tenant)` and `TenantUnknown`, and `AdmissionPolicy.admit(long tenantId)` built from `TenantRegistry`, in `gateway/domain/src/main/java/com/mikeflat/gateway/domain/quota` (design D-1); verify with `AdmissionPolicyTest` (snake_case names, e.g. `registered_id_is_admitted`, `unregistered_id_is_unknown`) via `./gradlew :domain:test`
- [x] 1.2 Add the `AdmitBusinessCall` use case in `gateway/app/src/main/java/com/mikeflat/gateway/app/quota`, built from `AdmissionPolicy` and delegating `admit(long tenantId)` to it (design D-1); verify with `AdmitBusinessCallTest` via `./gradlew :app:test`

## 2. Inbound adapter: OpenAPI contract and stub controller

- [x] 2.1 Add `GET /api/v1/resource` to `gateway/adapters/api/src/main/resources/openapi/gateway-api.yaml` per design D-5 (tag `Resource`, required `X-Tenant-Id` header, `MockResource` 200 schema, `ProblemDetail` 400 schema) and update the file's header comment; verify `./gradlew :adapters:api:openApiGenerate` produces `ResourceApi` and `MockResource` and no `ProblemDetail` model
- [x] 2.2 Add `StubController` in `…adapters.api.web` implementing `ResourceApi` and returning the constant `MockResource("mock-resource", "Served by the quota gateway stub")`; verify with `StubControllerTest` (standalone `MockMvc`: `200`, `application/json`, exact body) via `./gradlew :adapters:api:test`

## 3. Inbound adapter: tenant guard filter

- [x] 3.1 Add package-private `ProblemResponseWriter` in `…adapters.api.filter` writing `application/problem+json` with `type`, `title`, `status`, `detail`, `instance` through an injected `JsonMapper` (design D-3); verify with a `ProblemResponseWriterTest` on `MockHttpServletResponse` that asserts status, content type and the exact JSON fields
- [x] 3.2 Add package-private `TenantIdHeader` in `…adapters.api.filter` with `Missing`, `Malformed` and `Present(long)` and the parsing rule of design D-4 (null/blank → missing; 1–18 ASCII digits → present; anything else → malformed); verify with `TenantIdHeaderTest` (e.g. `blank_header_is_missing`, `plus_sign_is_malformed`, `nineteen_digits_are_malformed`, `leading_zeros_are_accepted`) via `./gradlew :adapters:api:test`
- [x] 3.3 Add `QuotaFilter` (`OncePerRequestFilter`) in `…adapters.api.filter` that parses `X-Tenant-Id` with `TenantIdHeader`, calls `AdmitBusinessCall` for a present id and switches over `Admission`: continue the chain on `Admitted`, write `400` with the missing / unknown detail otherwise, never echoing the header value; verify with `QuotaFilterTest` (`MockHttpServletRequest`, `MockFilterChain`, real `AdmitBusinessCall` and `AdmissionPolicy`) covering known, missing, empty, malformed and unknown ids and that the chain is not invoked on rejection, via `./gradlew :adapters:api:test`

## 4. Wiring and HTTP integration

- [x] 4.1 Declare the `AdmissionPolicy` bean in `DomainConfiguration`, the `AdmitBusinessCall` bean in `AppConfiguration`, and the `StubController` bean plus a `FilterRegistrationBean<QuotaFilter>` (URL pattern `/api/*`, dispatcher type `REQUEST`) in `ApiConfiguration` (design D-1, D-2); verify `./gradlew :bootstrap:test` still passes `ArchitectureTest` and the existing startup tests
- [x] 4.2 Extend `HttpProbe` with a `get(port, path, headers)` variant and add `BusinessEndpointTest` (`@SpringBootTest(RANDOM_PORT)`) covering the `business-api` scenarios: known tenant `200` with exact body and no rate-limit headers, identical bodies for two tiers, missing / empty / malformed / unknown id `400` with problem body and no echoed `<script>`, `GET /api/v1/other` `400` without and `404` with a known tenant, `/apix/resource` `404`, `/actuator/health` `200` without a header; verify via `./gradlew :bootstrap:test`
- [x] 4.3 Add `CustomTenantsFileBusinessEndpointTest` that starts the gateway with a two-tenant temp file (`tenants.file` via `@DynamicPropertySource`) and asserts `X-Tenant-Id: 2` → `200` and `3` → `400`; verify via `./gradlew :bootstrap:test`
- [x] 4.4 Replace `HealthEndpointTest.unknown_api_path_is_not_found` with a test asserting `404` on `GET /admin/snapshot`, and assert `404` on `GET /api/v2/resource` with a known tenant in `BusinessEndpointTest` (`gateway-runtime` delta); verify via `./gradlew :bootstrap:test`

## 5. Documentation and full check

- [x] 5.1 Update `gateway/README.md`: describe `GET /api/v1/resource`, the `X-Tenant-Id` guard and its `400` problem body, the layer split (`TenantIdHeader` → `AdmitBusinessCall` → `AdmissionPolicy`), remove "no business endpoint yet" and "the spec is a stub with no paths", and keep the 404 statement for undefined paths; verify by reading the changed sections against the `business-api` spec
- [x] 5.2 Run `./gradlew test` in `gateway/` and confirm every module passes, with no comments added in new code and all new test methods in snake_case (no services started; checks against a running stack are left to the user)
