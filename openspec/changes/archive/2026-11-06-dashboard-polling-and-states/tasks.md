# Tasks

Run checks only from `dashboard/` with `npm test`, `npm run lint` and `npm run build`; never start the dev server, nginx, the gateway or the stack. No code comments; test names in snake_case. Module and type names follow design.md D-1.

## 1. Domain: snapshot model, enforcement, connection state, view, poll delay

- [x] 1.1 Add `domain/snapshot.ts` (types mirroring the OpenAPI snapshot schema with nullable `metrics`, `rows`, `total`, `used`, `rejected`, `status`) and `domain/pollDelay.ts` (`nextPollDelay`); delete `domain/.gitkeep`; verify with `pollDelay.test.ts` (`fast_poll_waits_rest_of_interval`, `slow_poll_runs_next_immediately`, `delay_is_never_negative`) via `npm test`
- [x] 1.2 Add `domain/enforcement.ts` (`EnforcementStatus`, `HealthReport`, `enforcementStatusOf`); verify with `enforcement.test.ts` (`redis_down_is_degraded`, `redis_up_is_ok`, `missing_redis_component_is_unknown`, `unexpected_redis_status_is_unknown`) via `npm test`
- [x] 1.3 Add `domain/connection.ts` (`ConnectionState`, `nextConnectionState`, `failuresBeforeUnreachable = 2`) per design D-3; verify with `connection.test.ts` (`first_snapshot_makes_state_live`, `one_failure_keeps_live`, `two_failures_make_live_unreachable_with_last_snapshot`, `two_failures_while_loading_make_unreachable_without_data`, `success_after_unreachable_sets_reconnected_at`, `success_after_single_failure_does_not_set_reconnected_at`, `failures_while_unreachable_keep_last_snapshot`) via `npm test`
- [x] 1.4 Add `domain/dashboardView.ts` (`DashboardView`, `dashboardViewOf`, `reconnectedNoticeMillis = 3000`) per the D-3 table; verify with `dashboardView.test.ts` (`loading_has_no_snapshot_and_disabled_controls`, `stale_view_reports_whole_seconds_since_last_update`, `reconnected_notice_visible_for_three_seconds`, `null_metrics_mark_metrics_unavailable_and_keep_controls_enabled`, `degraded_enforcement_is_reported_independently`, `unreachable_disables_tier_controls`) via `npm test`; then `npm run lint` passes (no React or timers in domain)

## 2. Application: ports and polling loop

- [x] 2.1 Add `application/ports.ts` (`SnapshotSource`, `HealthSource`, `Scheduler`, `Cancel`) and the test double `test/support/manualScheduler.ts` (virtual `now`, `schedule`, `advanceBy`); delete `application/.gitkeep`; verify `npm run build` type-checks
- [x] 2.2 Add `application/polling.ts` (`startPolling`) per design D-2; verify with `polling.test.ts` using `ManualScheduler` (`first_poll_starts_immediately`, `next_poll_starts_one_interval_after_previous_start`, `slow_poll_is_never_overlapped`, `slow_poll_is_followed_immediately`, `poll_exceeding_timeout_is_aborted_and_reported_failed_once`, `polling_continues_after_failures`, `stop_aborts_in_flight_request_and_schedules_nothing`, `outcome_after_stop_is_ignored`) via `npm test`

## 3. Application: React hooks

- [x] 3.1 Add `application/DashboardPorts.tsx` (context + provider) and `application/usePolling.ts`; verify with `usePolling.test.tsx` (`unmount_stops_polling`, `remount_starts_a_fresh_loop`) via `npm test`
- [x] 3.2 Add `application/useDashboard.ts` (snapshot poll 1 s / 2 s timeout into the connection reducer, health poll 5 s / 2 s timeout into enforcement status with failures as `unknown`, 1 s `now` tick, returns `dashboardViewOf`); verify with `useDashboard.test.tsx` using `renderHook`, fake ports and `ManualScheduler` (`starts_loading`, `snapshot_success_makes_view_live`, `two_snapshot_failures_make_view_unreachable`, `health_redis_down_marks_enforcement_degraded`, `health_failure_clears_degraded`, `health_failures_never_make_gateway_unreachable`, `last_updated_seconds_advance_with_tick`) via `npm test`; then `npm run lint` passes

## 4. Outbound adapters: HTTP and time

- [x] 4.1 Add `adapters/api/httpSnapshotSource.ts` (`createHttpSnapshotSource(fetchFn)`, `toAdminSnapshot` validation, `SnapshotRequestFailed`); delete `adapters/api/.gitkeep`; verify with `httpSnapshotSource.test.ts` using a stubbed `fetch` with real `Response` objects (`requests_admin_snapshot_with_signal`, `valid_body_maps_to_snapshot`, `null_metrics_are_kept_null`, `server_error_rejects`, `client_error_rejects`, `non_json_body_rejects`, `body_without_period_rejects`, `network_error_rejects`) via `npm test`
- [x] 4.2 Add `adapters/api/httpHealthSource.ts` (`createHttpHealthSource(fetchFn)`, reads JSON for `200` and `503`); verify with `httpHealthSource.test.ts` (`redis_up_is_reported`, `redis_down_with_503_is_reported_not_rejected`, `missing_redis_component_reports_null`, `bad_gateway_html_rejects`, `network_error_rejects`) via `npm test`
- [x] 4.3 Add `adapters/time/browserScheduler.ts`; verify with `browserScheduler.test.ts` using `vi.useFakeTimers()` (`scheduled_callback_runs_after_delay`, `cancel_prevents_callback`, `now_reads_system_time`) via `npm test`; then `npm run lint` passes (no React in adapters)

## 5. UI: screen, banners and states

- [x] 5.1 Add presentational components in `ui/components/` (`LoadingIndicator`, `GatewayUnreachableBanner`, `DegradedEnforcementBanner`, `ReconnectedNotice`, `LastUpdatedNote`, `MetricsUnavailable`, `DataRegions`, `DashboardScreen`) per design D-5, and banner/stale styles with light and dark variables in `ui/index.css`; delete `ui/components/.gitkeep`; verify with `DashboardScreen.test.tsx` rendering `DashboardView` props (`loading_view_shows_indicator_and_no_digits`, `stale_view_greys_regions_and_shows_last_updated`, `metrics_unavailable_marks_traffic_and_totals_only`, `degraded_view_shows_yellow_banner_text`, `banners_render_above_regions`) via `npm test`
- [x] 5.2 Rewrite `ui/App.tsx` to render `DashboardScreen` from `useDashboard()`, and wire `main.tsx` (HTTP sources with `fetch`, `browserScheduler`, `DashboardPortsProvider`; remove its comment line); verify `npm run build` and `npm run lint` pass
- [x] 5.3 Rewrite `ui/App.test.tsx` to drive the real `App` in `DashboardPortsProvider` with fake sources and `ManualScheduler`, one test per `dashboard-states` scenario (`shows_heading_and_loading_until_first_snapshot`, `first_snapshot_shows_regions`, `one_failed_poll_shows_no_banner`, `two_failed_polls_show_gateway_unreachable`, `gateway_down_on_first_load_keeps_loading_with_banner`, `unreachable_greys_last_data_and_counts_seconds`, `recovery_removes_banner_and_shows_reconnected_briefly`, `no_reconnected_notice_after_single_failure`, `redis_down_health_shows_degraded_banner`, `redis_up_health_removes_degraded_banner`, `health_failure_removes_degraded_banner`, `null_metrics_show_metrics_unavailable_in_traffic_and_totals`, `metrics_return_removes_marker`, `degraded_and_metrics_unavailable_show_together`, `reconnected_while_redis_down_shows_notice_and_marker`); verify via `npm test`

## 6. Documentation and final checks

- [x] 6.1 Update `dashboard/README.md`: replace the "skeleton" intro, add the `src/adapters/time/` row to the layer table, and add a short "Live data and states" section (poll cadence and timeout, the states and their triggers, manual check of S7/S8 left to the user with the stack scripts); verify the README matches the implemented behaviour and file names
- [x] 6.2 Run `npm run lint`, `npm test` and `npm run build` in `dashboard/` together and confirm all pass; confirm `openspec validate dashboard-polling-and-states --strict` passes
