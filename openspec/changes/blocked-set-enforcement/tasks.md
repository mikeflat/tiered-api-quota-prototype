# Tasks

## 1. Domain: blocked set, period arithmetic and decision

- [x] 1.1 Add `QuotaSettings.secondsUntilNextPeriod(long epochSecond)` and `QuotaSettings.reachesLimit(Tier tier, long used)` (design D-1, D-3); verify with new `QuotaSettingsTest` cases `seconds_until_next_period_in_middle_of_period`, `seconds_until_next_period_in_last_second_is_one`, `seconds_until_next_period_on_boundary_is_whole_period`, `used_equal_to_limit_reaches_limit`, `used_below_limit_does_not_reach_limit` via `./gradlew :domain:test`
- [x] 1.2 Add `UsageEvent.rejectedAt(Tenant, Instant, QuotaSettings)`; verify with `UsageEventTest` case `rejected_event_captures_period_and_second` via `./gradlew :domain:test`
- [x] 1.3 Add `BlockedTenants` in `domain.quota` per design D-2 (`AtomicReference` of a per-period record holding a concurrent key set; `isBlocked(tenantId, periodIndex)`, `block(tenantId, periodIndex)`, swap on a newer period, ignore older periods); verify with `BlockedTenantsTest`: `nothing_is_blocked_initially`, `blocked_tenant_is_blocked_in_its_period`, `other_tenants_stay_unblocked`, `blocked_tenant_is_not_blocked_in_next_period`, `block_in_new_period_discards_previous_blocks`, `block_for_older_period_is_ignored`, `readers_see_blocks_made_by_another_thread`, via `./gradlew :domain:test`
- [x] 1.4 Add `Admission.Blocked(Tenant tenant, long retryAfterSeconds)` and change `AdmissionPolicy` to `AdmissionPolicy(TenantRegistry, BlockedTenants, QuotaSettings)` with `admit(long tenantId, long epochSecond)` (design D-3); update `AdmissionPolicyTest`: `unblocked_known_tenant_is_admitted`, `blocked_tenant_is_blocked_with_retry_after`, `tenant_blocked_in_previous_period_is_admitted`, `unknown_tenant_is_unknown_even_if_id_was_blocked`, via `./gradlew :domain:test`

## 2. Application: rejected events and blocking on the writer

- [x] 2.1 Add the `RecordedUsageListener` interface in `app.usage` and give `UsageRecorder` a listener called with the returned `used` after each successful record (design D-1, D-4); update `UsageRecorderTest`: `listener_receives_event_and_used_after_record`, `listener_is_not_called_when_record_fails`, keeping existing cases green, via `./gradlew :app:test`
- [x] 2.2 Add `BlockTenantsOverLimit` in `app.quota` implementing `RecordedUsageListener` (allowed only, event period must be current, current tier from the registry, `reachesLimit` → `block`) per design D-4; verify with `BlockTenantsOverLimitTest` using `Clock.fixed`: `allowed_event_reaching_limit_blocks_tenant`, `allowed_event_below_limit_does_not_block`, `limit_follows_tenant_tier`, `rejected_event_never_blocks`, `event_of_past_period_does_not_block`, via `./gradlew :app:test`
- [x] 2.3 Change `AdmitBusinessCall` to read the clock once, call `policy.admit(tenantId, epochSecond)` and submit an allowed event on `Admitted`, a rejected event on `Blocked`, none on `TenantUnknown` (design D-3); update `AdmitBusinessCallTest`: `blocked_call_submits_rejected_event_for_current_period`, `blocked_call_returns_retry_after_to_next_boundary`, keeping existing cases green, via `./gradlew :app:test`
- [x] 2.4 Add an overshoot test in `:app` wiring `AdmitBusinessCall`, `UsageRecorder` and `BlockTenantsOverLimit` with a fake counter that increments per tenant and a manual executor that holds events until released (Bronze limit `5`): `requests_pass_until_writer_applies_limit_event` (8 admitted while stalled, release, `used` 8, next is `Blocked`) and `tenant_is_blocked_once_limit_event_is_applied` (sequential, 6th is `Blocked`); verify via `./gradlew :app:test`

## 3. Inbound adapter: 429 response

- [x] 3.1 Add the `Admission.Blocked` case to `QuotaFilter` writing `Retry-After` and a `429` problem body with detail "Quota for the current period is exhausted", without calling the chain (design D-5); update `QuotaFilterTest`: `blocked_tenant_gets_too_many_requests`, `blocked_response_carries_retry_after_seconds`, `blocked_response_is_problem_json_without_usage_numbers`, `blocked_request_does_not_reach_handler`, keeping the `400` cases green, via `./gradlew :adapters:api:test`
- [x] 3.2 Add the `429` response with a `Retry-After` header (integer seconds) and `ProblemDetail` body to `GET /api/v1/resource` in `gateway-api.yaml`; verify the OpenAPI generation and `StubControllerTest` still pass via `./gradlew :adapters:api:build`

## 4. Bootstrap: wiring and integration tests

- [x] 4.1 Wire `BlockedTenants` and the new `AdmissionPolicy` in `DomainConfiguration`, and `BlockTenantsOverLimit` plus `UsageRecorder` with its listener in `AppConfiguration`; verify the existing `:bootstrap` tests (`ArchitectureTest`, `BusinessEndpointTest`, `UsageRecordingIntegrationTest`, `UsageRecordingWithoutRedisTest`, …) still pass via `./gradlew :bootstrap:test`
- [x] 4.2 Add a `MutableClock` test helper in bootstrap test sources (a `Clock` whose instant can be set and advanced); verify by its use in 4.3
- [x] 4.3 Add `QuotaEnforcementIntegrationTest` (`@SpringBootTest(RANDOM_PORT)`, Testcontainers Redis with `@ServiceConnection`, `quota.limits.bronze=5`, `@Primary` `MutableClock`, Redis flushed and clock reset before each test, `Eventually` before asserting blocks): `tenant_is_rejected_after_reaching_limit`, `rejection_carries_retry_after_to_next_boundary`, `rejections_are_counted_as_rejected_not_used`, `other_tenants_are_not_affected_by_a_block`, `burst_counts_match_responses`, `blocked_tenant_is_served_again_in_next_period`, `tenant_is_blocked_again_in_next_period`; verify via `./gradlew :bootstrap:test` (Docker required)
- [x] 4.4 Add `BlockedTenantWhileRedisDownTest` with its own Redis container: block a Bronze tenant, pause the container, then `blocked_tenant_stays_blocked_while_redis_is_unreachable` (`429` with `Retry-After`, returned well under the Redis timeout) and `unblocked_tenant_is_served_while_redis_is_unreachable`, unpausing in cleanup; verify via `./gradlew :bootstrap:test` (Docker required)

## 5. Documentation

- [x] 5.1 Update `gateway/README.md`: current state (over-limit tenants get `429` with `Retry-After`), module table (`BlockedTenants`, `Admission.Blocked`, `BlockTenantsOverLimit`, `RecordedUsageListener`), the business API section (`429` response), a short quota-enforcement section (blocked set per period, writer blocks at `used ≥ limit`, bounded overshoot, empty after restart, stays enforced while Redis is down); verify by reading the changed sections against the `quota-enforcement` spec

## 6. Full check

- [x] 6.1 Run `./gradlew test` in `gateway/` and confirm every module passes, with no comments in new code and all new test methods in snake_case (no services started; checks against a running stack are left to the user)
