# Tasks

Run tests only through the generator's own commands from `generator/` (`npm test`, `npm run typecheck`); never start the stack, the component scripts, `npm start` against a real gateway or containers by hand. A `node:http` server created inside a Vitest test is a test fixture and is allowed. No code comments; test names in snake_case. Module names follow design.md decision 1; the generator stays flat (no layers).

## 1. Bring the skeleton in line with project rules

- [x] 1.1 Remove the JSDoc comments from `src/config.ts` and the inline comment from `src/main.ts`, and rename the existing tests in `config.test.ts` and `main.test.ts` to snake_case (e.g. `defaults_to_localhost_8080_when_unset`, `invalid_gateway_url_exits_non_zero_naming_variable_and_value`); verify `npm run typecheck` and `npm test` pass

## 2. Settings and file loading

- [x] 2.1 Add `readFilePaths(env)` to `src/config.ts` (`TENANTS_FILE`, `TRAFFIC_CONFIG_FILE`, blank → defaults resolved from `import.meta.url`); verify with `config.test.ts` cases (`tenants_path_defaults_to_repository_config`, `traffic_path_defaults_to_generator_traffic_json`, `blank_paths_use_defaults`, `paths_can_be_overridden`)
- [x] 2.2 Add `src/tenants.ts` (`parseTenantIds`, `loadTenantIds`); verify with `tenants.test.ts` (`reads_ids_and_ignores_other_fields`, `seeded_file_has_fifteen_tenants`, `missing_file_names_path`, `invalid_json_names_path`, `empty_array_is_rejected`, `non_positive_or_fractional_id_is_rejected`, `duplicate_id_is_rejected`)
- [x] 2.3 Add `generator/traffic.json` with the defaults of design decision 8 and `src/traffic-config.ts` (`parseTrafficConfig`, `loadTrafficConfig`); verify with `traffic-config.test.ts` (`optional_fields_take_defaults`, `unlisted_tenants_get_default_weight`, `zero_weight_tenants_are_dropped`, `rate_must_be_positive_and_at_most_5000`, `negative_or_non_numeric_weight_is_rejected`, `unknown_key_is_rejected`, `weight_for_unknown_tenant_names_the_id`, `all_zero_weights_are_rejected`, `invalid_integer_limits_are_rejected`, `errors_name_path_and_field`)
- [x] 2.4 Add the FR-25 check `default_shape_puts_tenants_in_ok_near_and_over` in `traffic-config.test.ts` (default `traffic.json` + `config/tenants.json` + limits 100/1,000/10,000, 60 s, 80 %); verify it passes and that a shape with every tenant under its limit fails the check (uniform weights at 200 req/s also give all three states, so they are not a negative case)

## 3. Pacing, weighted pick, outcomes and summary (pure helpers)

- [x] 3.1 Add `src/pacing.ts` (`createPacer` with wall-clock due count and one-second catch-up cap); verify with `pacing.test.ts` (`releases_rate_times_elapsed_seconds`, `handles_fractional_rates_without_drift`, `same_instant_releases_nothing_twice`, `catch_up_after_stall_is_capped_at_one_second`, `pacing_continues_at_rate_after_stall`)
- [x] 3.2 Add `src/weighted-pick.ts` (cumulative weights + binary search, injected `random`); verify with `weighted-pick.test.ts` (`distribution_follows_weights_within_two_points` with a seeded generator, `random_zero_and_near_one_map_to_first_and_last`, `single_tenant_is_always_picked`)
- [x] 3.3 Add `src/outcome.ts` (`Outcome`, `classifyStatus`, `classifyError`); verify with `outcome.test.ts` (`status_200_is_ok`, `status_429_is_rejected`, `other_status_is_error_with_status_reason`, `timeout_error_reason_is_timeout`, `connection_refused_reason_is_code`, `stop_abort_reason_is_stopped`, `unknown_error_uses_its_name`)
- [x] 3.4 Add `src/summary.ts` (`createSummary` with `recordOutcome`, `recordSkipped`, `takeLine`); verify with `summary.test.ts` (`line_contains_all_counts`, `counts_reset_after_take`, `last_error_shown_only_with_errors`, `stopped_outcomes_are_not_counted`, `skipped_is_not_counted_as_sent`)

## 4. Request sending

- [x] 4.1 Add `src/send-request.ts` (endpoint built once, `X-Tenant-Id`, combined timeout/stop signal, body drained, never rejects); verify with `send-request.test.ts` using a fake `fetch` (`sends_get_to_resource_with_tenant_header`, `endpoint_keeps_gateway_base_path`, `drains_response_body`, `maps_status_to_outcome`, `timeout_becomes_timeout_error`, `stop_signal_becomes_stopped`, `thrown_error_never_escapes`)

## 5. Main loop, shutdown and docs

- [x] 5.1 Rewrite `src/main.ts`: load URL, paths, tenants and traffic config (exit 1 with message on any error, before timers), log the new startup line, run the 20 ms tick (pacer → in-flight check → pick → send → summary) and the 10 s summary interval, shut down per design decision 7; the tick, summary and stop logic live in a flat `src/generator.ts` (`startGenerator` with injected fetch, log, random and clock) so it can be tested in-process, and `main.ts` keeps loading, the startup line and signal wiring; verify `npm run typecheck`
- [x] 5.2 Extend `main.test.ts` with an in-test `node:http` server on port 0 and temp config files: `startup_line_names_gateway_tenants_and_rate`, `requests_carry_tenant_ids_from_tenants_file`, `only_weighted_tenants_receive_traffic`, `invalid_tenants_file_exits_non_zero_naming_path`, `invalid_traffic_config_exits_non_zero_naming_field`, `keeps_running_without_a_gateway`, `sigint_logs_final_summary_and_stop_line_and_exits_zero`, `sigint_exits_quickly_while_gateway_hangs`, `in_flight_never_exceeds_max_against_hanging_gateway` (small `maxInFlight`, server counts open requests); loop behaviour (rate, outcomes, outage and resume, in-flight cap, timeout, quick stop) is tested in-process in `generator.test.ts` with the `test-gateway.fixture.ts` server, and the child-process SIGINT test is skipped on Windows, where Node cannot deliver SIGINT to a child; verify `npm test` passes
- [x] 5.3 Update `generator/README.md` (no longer a skeleton; behaviour, `traffic.json` fields and defaults, weight table, env variables, summary line format, module table, Docker run with the tenants mount) and `generator/.env.example` (`TENANTS_FILE`, `TRAFFIC_CONFIG_FILE`); verify every documented field and variable matches `traffic-config.ts` and `config.ts`

## 6. Container and stack wiring

- [x] 6.1 Update `generator/Dockerfile` (copy `traffic.json`, `ENV TENANTS_FILE=/config/tenants.json`, keep non-root `node`, no tests in the image) and check `generator/.dockerignore` still excludes `*.test.ts` and `node_modules`; verify by reading the files (building the image is left to the user)
- [x] 6.2 Update `docker-compose.yml`: generator volumes `./config/tenants.json:/config/tenants.json:ro` and `./generator/traffic.json:/app/traffic.json:ro`, and refresh the gateway comment that says the generator reads the file "later"; verify with `docker compose config` (static validation only, nothing is started)

## 7. Integration check

- [x] 7.1 Run `npm run typecheck` and `npm test` in `generator/` and confirm both pass; list for the user the stack checks that need a running stack and are not run here: `start` shows the generator startup line with `tenants=15`, summaries show `200` and `429`, `stop-gateway` turns them into errors and `start-gateway` brings `200` back, editing `ratePerSecond` then `stop-generator`/`start-generator` shows the new rate, `docker stop` ends the log with a summary and the stop line
