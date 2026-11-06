# Tasks

Run checks only from `dashboard/` with `npm test`, `npm run lint` and `npm run build`; never start the dev server, nginx, the gateway or the stack. No code comments; test names in snake_case; tests under `dashboard/test/` mirroring `src/`. Module and type names follow design.md D-1.

## 1. Domain: countdown, traffic summary, trend geometry, view

- [x] 1.1 Add `domain/resetCountdown.ts` (`secondsUntilReset(period, receivedAt, now)` per design D-2); verify with `test/domain/resetCountdown.test.ts` (`fresh_snapshot_returns_seconds_left`, `subtracts_whole_seconds_since_receipt`, `partial_second_is_not_subtracted`, `never_goes_below_zero`) via `npm test`
- [x] 1.2 Add `domain/trafficSummary.ts` (`currentSecondOf`, `trafficPeaksOf`); verify with `test/domain/trafficSummary.test.ts` (`current_second_is_newest_entry`, `current_second_is_null_without_entries`, `peaks_are_max_of_each_series`, `peaks_are_zero_without_entries`) via `npm test`
- [x] 1.3 Add `domain/trendGeometry.ts` (`trendSlots = 60`, `TrendPoint`, `trendLinesOf(perSecond, size)` per design D-3); verify with `test/domain/trendGeometry.test.ts` (`full_minute_spans_left_to_right_edge`, `lines_share_one_scale_from_zero`, `peak_value_is_drawn_at_top`, `all_zero_minute_is_flat_on_bottom`, `fewer_entries_are_right_aligned`, `more_than_sixty_entries_keep_newest_sixty`, `coordinates_are_rounded_to_one_decimal`) via `npm test`
- [x] 1.4 Extend `domain/dashboardView.ts` with `connectionStatus`, `enforcement` and `secondsUntilReset` (`null` unless the connection is live); extend `test/domain/dashboardView.test.ts` (`loading_is_connecting_without_countdown`, `live_is_connected_with_countdown`, `one_failure_stays_connected`, `unreachable_has_no_countdown`, `enforcement_status_is_passed_through`, `countdown_counts_down_with_now`) and fix existing view fixtures for the new fields; verify `npm test` and `npm run lint` pass

## 2. Test fixtures

- [x] 2.1 Extend `test/support/snapshots.ts` with a helper building a full minute of `perSecond` entries (e.g. `minuteOfTraffic(newestSecond, valueFor)`) and a `metricsWith(overrides)` helper, keeping `snapshotWith` / `snapshotWithoutMetrics` working; verify the whole suite still passes via `npm test`

## 3. UI: formatting and header

- [x] 3.1 Add `ui/format.ts` (`formatCount`, `formatCountdown`, connection and enforcement labels per design D-4); verify with `test/ui/format.test.ts` (`counts_use_thousands_separator`, `countdown_under_an_hour_is_minutes_and_seconds`, `countdown_from_an_hour_includes_hours`, `zero_countdown_is_zero_seconds`, `missing_countdown_is_a_dash`, `status_labels_match_header_texts`) via `npm test`
- [x] 3.2 Add `ui/components/HeaderStatus.tsx` and render it in `DashboardScreen`'s `<header>` in every state, with status classes and header layout in `ui/index.css` (light and dark); extend `test/ui/components/DashboardScreen.test.tsx` (`header_shows_connecting_and_dash_while_loading`, `header_shows_connected_ok_and_countdown_when_live`, `header_shows_unreachable_and_dash_when_stale`, `header_shows_degraded_enforcement`, `header_shows_unknown_enforcement`, `loading_main_area_still_has_no_digits`) and update `liveView` / `loadingView` for the new view fields; verify `npm test` and `npm run lint` pass

## 4. UI: traffic and period totals panels

- [x] 4.1 Add `ui/components/TrafficTrend.tsx` (SVG `role="img"` with the peak-based accessible name, two polylines from `trendLinesOf`, text legend) and its line colours `--allowed` / `--rejected` in `ui/index.css`; verify with `test/ui/components/TrafficTrend.test.tsx` (`trend_is_an_image_named_with_last_sixty_seconds_and_peaks`, `draws_sixty_points_per_line`, `legend_names_allowed_and_rejected`, `all_zero_minute_renders_flat_lines`) via `npm test`
- [x] 4.2 Add `ui/components/TrafficPanel.tsx` (current allowed / rejected req/s, "—" without entries, then `TrafficTrend`); verify with `test/ui/components/TrafficPanel.test.tsx` (`shows_newest_second_allowed_and_rejected`, `shows_zero_for_quiet_second`, `shows_dash_without_entries`, `includes_trend`) via `npm test`
- [x] 4.3 Add `ui/components/PeriodTotalsPanel.tsx` (Allowed, Rejected, Near limit, Over limit with `formatCount`); verify with `test/ui/components/PeriodTotalsPanel.test.tsx` (`shows_totals_with_thousands_separator`, `shows_near_and_over_counts`, `shows_zeros_for_new_period`) via `npm test`
- [x] 4.4 Change `DataRegions` to take `metrics: TrafficMetrics | null` and render the two panels or `MetricsUnavailable`, and pass `view.snapshot?.metrics ?? null` from `DashboardScreen`; extend `DashboardScreen.test.tsx` (`live_view_shows_traffic_and_totals_numbers`, `metrics_unavailable_shows_no_digits_or_trend_in_traffic_and_totals`, `stale_view_keeps_last_numbers_greyed`) and keep `metrics_unavailable_marks_traffic_and_totals_only` passing; verify `npm test` and `npm run lint` pass

## 5. Integration through the real App

- [x] 5.1 Extend `test/ui/App.test.tsx` driving the real `App` with fake sources and `ManualScheduler`, one test per new spec scenario group (`header_connecting_then_connected`, `header_unreachable_after_two_failures_and_back`, `header_enforcement_follows_health_polls`, `countdown_ticks_between_polls_and_restarts_on_new_period`, `countdown_is_dash_while_unreachable_and_returns`, `countdown_shown_while_metrics_unavailable`, `traffic_and_totals_follow_polls`, `panels_replaced_by_metrics_unavailable_and_return`, `panels_keep_last_numbers_while_unreachable`); verify via `npm test`

## 6. Documentation and final checks

- [x] 6.1 Update `dashboard/README.md`: intro mentions the header statuses and traffic panels, and a short "Header and traffic panels" section (connection/enforcement labels, countdown from `secondsLeft` and "—" without live data, current req/s, 60 s trend, totals and near/over counts, behaviour with metrics unavailable and when unreachable; manual S3/S7/S8 checks left to the user with the stack scripts); verify the README matches the implemented file names and texts
- [x] 6.2 Run `npm run lint`, `npm test` and `npm run build` in `dashboard/` together and confirm all pass; confirm `openspec validate dashboard-traffic-panels --strict` passes
