# Tasks

Run tests only through Gradle from `gateway/` (unit and Testcontainers tests); never start the stack, `bootRun` or containers by hand. No code comments; test methods in snake_case.

## 1. Application layer: outage tracking and alerts port

- [x] 1.1 Add `RecordingOutage` in `:app` `app.usage` (`failed()` is `true` only on the first failure after a success or at startup; `succeeded()` is `true` only when it ends an outage; no counts); verify with `RecordingOutageTest` (`first_failure_starts_outage`, `further_failures_do_not_start_another`, `success_ends_outage`, `success_without_outage_ends_nothing`, `failure_after_recovery_starts_new_outage`) via `./gradlew :app:test`
- [x] 1.2 Declare the outbound port `UsageRecordingAlerts` in `app.usage` (`recordingFailing(UsageRecordingFailedException)`, `recordingResumed()`); verify `./gradlew :app:compileJava` and the architecture test still pass
- [x] 1.3 Extend `UsageRecorder` to take `UsageRecordingAlerts` and report outage start and recovery from the writer path through `RecordingOutage`; verify with new `UsageRecorderTest` cases (`twenty_failures_raise_one_outage_alert`, `recovery_raises_one_resume_alert`, `drops_raise_no_alert`, `submit_never_calls_alerts`) and existing cases via `./gradlew :app:test`

## 2. Adapter: SLF4J alerts

- [x] 2.1 Implement `Slf4jUsageRecordingAlerts` in `:adapters:infra` `adapters.infra.logging` with the two messages from design.md (one-line WARN with the root cause message and no stack trace, INFO on resume, no event counts in either); verify with `Slf4jUsageRecordingAlertsTest` using a Logback `ListAppender` (`outage_is_one_warning_with_cause_and_consequences`, `resume_is_one_info_line`) via `./gradlew :adapters:infra:test`

## 3. Wiring

- [x] 3.1 Wire `Slf4jUsageRecordingAlerts` as a `@Bean` in `InfraConfiguration` and pass it to `UsageRecorder` in `AppConfiguration`; verify `UsageRecordingWithoutRedisTest` and `ArchitectureTest` pass via `./gradlew :bootstrap:test --tests '*UsageRecordingWithoutRedisTest' --tests '*ArchitectureTest'`

## 4. Health: Redis component and probes

- [x] 4.1 In `application.yaml` enable `management.health.redis.enabled`, set `management.endpoint.health.show-components: always` (keep `show-details: never`), enable `probes.enabled` and pin the `liveness` / `readiness` groups to `livenessState` / `readinessState`; replace the outdated comments there. Verify with tasks 4.2 and 4.3
- [x] 4.2 Move `HealthEndpointTest` to a Testcontainers Redis and assert `200` `UP`, `components.redis` holding only `status` `UP`, no Redis host or version in the body, probes `200` `UP` without a `redis` component, `/actuator/env` and `/actuator/beans` still `404`; run `./gradlew :bootstrap:test --tests '*HealthEndpointTest'`
- [x] 4.3 Add `HealthWithoutRedisTest` (closed Redis port): startup succeeds, `/actuator/health` `503` JSON with `"status":"DOWN"` and Redis `DOWN` and no error message, liveness and readiness `200` `UP`; run `./gradlew :bootstrap:test --tests '*HealthWithoutRedisTest'`
- [x] 4.4 Switch `BusinessEndpointTest.health_needs_no_tenant_header` and `ServerPortTest.health_answers_on_configured_port` (both run without Redis) to `/actuator/health/liveness`; check `StartupFailureTest` and `TenantRegistryStartupTest` for health-UP assumptions without Redis and switch them likewise; run `./gradlew :bootstrap:test`
- [x] 4.5 Change the `gateway/Dockerfile` `HEALTHCHECK` to `/actuator/health/liveness` (keep the `SERVER_PORT` shell form); verify by reading the Dockerfile (container build and run are left to the user)

## 5. Redis stopped mid-run

- [x] 5.1 Add `RedisStoppedMidRunTest` (Testcontainers Redis, `MutableClock`, Bronze limit `5`, `OutputCaptureExtension`): block tenant `1`, bring tenant `2` to `used` `3`, pause Redis; assert tenant `1` gets `429` with `Retry-After` and tenant `2` gets `10 × 200` without waiting for the Redis timeout and is not blocked, `/actuator/health` answers `503` with Redis `DOWN` within 2 s while liveness and readiness answer `200` within 1 s, and exactly one outage warning is logged with no lost-event counts; run `./gradlew :bootstrap:test --tests '*RedisStoppedMidRunTest'`
- [x] 5.2 In the same test class, cover recovery: unpause Redis, then health returns `200` `UP`, `quota:used:2:{p}` is at least `3` (calls that timed out during the pause may be applied late), exactly one resume line is logged, and tenant `2` is blocked again once a recorded `used` reaches `5`; plus a separate case where the period rolls over during the outage and tenant `1` gets `200` in the new period with no tenant blocked; run the class again

## 6. Documentation and full run

- [x] 6.1 Update `gateway/README.md`: Health section (Redis component, `503` while Redis is down, liveness/readiness paths, Docker `HEALTHCHECK` on liveness), Usage recording section (outage and resume log lines; lost events are not tracked or logged, replacing the "logging them comes with `redis-failure-and-health`" note), Quota enforcement "Redis down" (no new blocks, resumes after return), test list; verify every documented path exists in `application.yaml` / Dockerfile
- [x] 6.2 Run the whole gateway suite `./gradlew test` from `gateway/` and confirm it passes; leave the stack checks of the `stack-runtime` scenarios (`stop-redis` / `start-redis`, container health) to the user
