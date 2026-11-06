# Tasks

Run tests only through Gradle from `gateway/` (unit and Testcontainers tests); never start the stack, `bootRun` or containers by hand. No code comments; test methods in snake_case. The port signature change in group 2 breaks compilation of `:adapters:infra` and `:bootstrap` until groups 3 and 4 are done; run only the module tests named in each task until then.

## 1. Domain: usage limits

- [x] 1.1 Add record `UsageLimits(long limit, long nearBoundary)` in `:domain` `domain.quota` with invariants (`limit ≥ 1`, `1 ≤ nearBoundary ≤ limit`) and `reachedBy(long used)`; verify with `UsageLimitsTest` (`rejects_limit_below_one`, `rejects_near_boundary_above_limit`, `used_at_limit_reaches_it`, `used_below_limit_does_not_reach_it`) via `./gradlew :domain:test`
- [x] 1.2 Add `QuotaSettings.limitsOf(Tier)` built from `limitOf` and `nearBoundary`; verify with new `QuotaSettingsTest` cases (`bronze_limits_are_100_and_80`, `gold_limits_are_10000_and_8000`, `limit_of_one_has_near_boundary_one`) via `./gradlew :domain:test`

## 2. Application: limits resolved by the writer

- [x] 2.1 Add `TenantUsageLimits` in `:app` `app.usage` (`Optional<UsageLimits> of(long tenantId)` from `TenantRegistry` + `QuotaSettings`); verify with `TenantUsageLimitsTest` (`known_tenant_gets_limits_of_its_tier`, `unknown_tenant_has_no_limits`) via `./gradlew :app:test`
- [x] 2.2 Change ports to `UsageCounter.record(UsageEvent, UsageLimits)` and `RecordedUsageListener.usageRecorded(UsageEvent, long, UsageLimits)`; make `UsageRecorder` take `TenantUsageLimits`, resolve limits per event on the writer, skip an event of an unknown tenant without a counter call, and pass the same limits to counter and listener; update `UsageRecorderTest` doubles and add `limits_of_current_tier_are_passed_to_counter_and_listener`, `event_of_unknown_tenant_is_skipped`, `failure_with_limits_resolved_is_counted_as_failed`; verify via `./gradlew :app:test`
- [x] 2.3 Make `BlockTenantsOverLimit` use `limits.reachedBy(used)` and drop its `TenantRegistry` dependency; remove `QuotaSettings.reachesLimit` and its tests if nothing else calls it; update `BlockTenantsOverLimitTest` (incl. `silver_limits_do_not_block_at_bronze_limit`) and `QuotaOvershootTest` to the new signature; verify `./gradlew :domain:test :app:test` and that the overshoot test still passes unchanged in its bounds

## 3. Adapter: record script and Redis counter

- [x] 3.1 Add key builders `secondKey(outcome, epochSecond)`, `periodTotalKey(outcome, p)`, `nearKey(p)`, `overKey(p)` and constant `SECOND_KEY_TTL_SECONDS = 120` to `RedisUsageCounter`; change `record` to the new port signature and pass the six keys and `ARGV [outcome, periodTtl, secondTtl, limit, nearBoundary, tenantId]` from design.md decision 4; adapt existing `RedisUsageCounterTest` calls; verify `./gradlew :adapters:infra:compileTestJava`
- [x] 3.2 Extend `redis/record-usage.lua` with per-second and period-total increments (TTL on creation) and near/over membership with `EXPIRE … NX`; verify with new `RedisUsageCounterTest` cases (`allowed_events_of_several_tenants_add_up_per_second`, `rejected_event_increments_rejected_second_and_total`, `late_event_counts_toward_its_own_second_and_period`, `period_totals_add_up_across_tenants`, `second_key_ttl_is_120_seconds_regardless_of_period`, `period_total_and_set_ttl_is_two_periods`, `later_set_member_keeps_original_ttl`, `recreated_set_gets_ttl`) via `./gradlew :adapters:infra:test`
- [x] 3.3 Cover status transitions with Testcontainers cases (`used_at_near_boundary_joins_near`, `used_below_near_boundary_joins_neither`, `reaching_limit_moves_from_near_to_over`, `overshoot_stays_over_only`, `rejected_event_keeps_over_status`, `rejected_event_without_usage_joins_neither`, `late_event_updates_sets_of_its_own_period`, `status_follows_limits_passed_in`, `allowed_event_below_near_writes_only_three_keys`, `script_cache_flush_still_records_metrics`) via `./gradlew :adapters:infra:test`

## 4. Wiring and end-to-end

- [x] 4.1 In `AppConfiguration` add the `TenantUsageLimits` bean, pass it to `UsageRecorder`, and drop the registry from `BlockTenantsOverLimit`; update test doubles in `UsageWriterExecutorTest` and `UsageRecordingWithoutRedisTest`; verify `./gradlew :bootstrap:test --tests '*ArchitectureTest' --tests '*UsageRecording*' --tests '*UsageWriterExecutorTest'`
- [x] 4.2 Add `TrafficMetricsRecordingIntegrationTest` (`:bootstrap`, Testcontainers Redis, fixed clock, Bronze limit `5`): send requests for tenants `1` and `9` over HTTP until tenant `1` gets `429`, wait for the writer, then assert `traffic:allowed:{s}`, `traffic:rejected:{s}`, both period totals, tenant `1` in `quota:over:{p}` only and blocked, tenant `9` in neither set; verify via `./gradlew :bootstrap:test --tests '*TrafficMetricsRecordingIntegrationTest'`
- [x] 4.3 Run the quota and Redis-outage integration tests (`QuotaEnforcementIntegrationTest`, `BlockedTenantWhileRedisDownTest`, `RedisStoppedMidRunTest`) and confirm they pass unchanged, which covers "metrics are not recorded while Redis is unavailable"

## 5. Documentation and full run

- [x] 5.1 Update `gateway/README.md`: usage recording section (what the one script call writes, the six keys with TTLs, status rule against the limit current at write time, Redis ≥ 7 for `EXPIRE NX`), module table (`TenantUsageLimits`, `UsageLimits`, updated port signatures), test list; verify each documented key name matches the `RedisUsageCounter` key builders
- [x] 5.2 Run the whole gateway suite `./gradlew test` from `gateway/` and confirm it passes; reading the new keys from a running stack (e.g. `redis-cli`) is left to the user
