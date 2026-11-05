# Tasks

All paths are relative to `/gateway` unless stated otherwise. See design.md D-1..D-8.

## 1. Remove template-specific code and resources

- [x] 1.1 Delete all clientsim main sources, including the four marker classes: `domain/.../domain/**`, `app/.../app/**`, `adapters/api/.../adapters/api/**`, `adapters/infra/.../adapters/infra/**` under `dev/clientsim/backend`; verify with a file search that no `.java` file remains under `src/main/java` of `domain`, `app`, `adapters/api`, `adapters/infra`
- [x] 1.2 Delete `adapters/api/src/main/resources/openapi/calls-api.yaml` and `adapters/infra/src/main/resources/redis/`; verify neither path exists
- [x] 1.3 Delete the tests for removed code (`CallsEndpointTest`, `RedisOutageTest`, `BackendRestartTest`, `RedisTestContainer`, `CallsControllerTest`, `CallCountsMapperTest`, `RedisCallCounterStoreIT`, `RecordCallUseCaseTest`, `ClientIdTest`) and the root `gateway/.gitkeep`; verify only `bootstrap/src/test` remains and holds `ArchitectureTest`, `HealthEndpointTest`, `ServerPortTest`, `HttpProbe`

## 2. Build definition

- [x] 2.1 Rewrite `settings.gradle.kts` (`rootProject.name = "gateway"`, same five includes) and `build.gradle.kts` (group `com.mikeflat`, plugins unchanged); verify `./gradlew projects` lists `:domain`, `:app`, `:adapters:api`, `:adapters:infra`, `:bootstrap`
- [x] 2.2 Keep `gradle/libs.versions.toml` entries and versions as in the template, updating only comments that mention the old project; verify every alias used by the build scripts resolves (`./gradlew help` succeeds)
- [x] 2.3 Update module build scripts per design D-4: same dependencies as the template; in `adapters:api` point `inputSpec` to `src/main/resources/openapi/gateway-api.yaml` and the generated packages to `com.mikeflat.gateway.adapters.api.generated.{api,model}`; in `bootstrap` name the `bootJar` `gateway.jar`; rewrite comments for this project; verify `./gradlew assemble` produces only `bootstrap/build/libs/gateway.jar`

## 3. Module packages, stub OpenAPI spec and architecture-test setup

- [x] 3.1 Add `.gitkeep` to `domain/src/main/java/com/mikeflat/gateway/domain/`, `app/src/main/java/com/mikeflat/gateway/app/`, `adapters/api/src/main/java/com/mikeflat/gateway/adapters/api/` and `adapters/infra/src/main/java/com/mikeflat/gateway/adapters/infra/`; verify `./gradlew :domain:compileJava :app:compileJava :adapters:infra:compileJava` passes (`NO-SOURCE`)
- [x] 3.2 Create the stub `adapters/api/src/main/resources/openapi/gateway-api.yaml` (OpenAPI 3.0.3, `info` title "Quota gateway API", `paths: {}`); verify `./gradlew :adapters:api:compileJava` passes; if the generator rejects empty `paths`, apply the fallback from design Risks (placeholder `GET /api/v1/resource` operation, no controller) and verify again
- [x] 3.3 Add `bootstrap/src/test/resources/archunit.properties` with `archRule.failOnEmptyShould=false` and a comment to remove it once every module has classes; verify the file is on the `bootstrap` test classpath after `./gradlew :bootstrap:processTestResources`

## 4. Bootstrap wiring, configuration and tests

- [x] 4.1 Create `com.mikeflat.gateway.bootstrap.GatewayApplication` (scan limited to its package, imports the four configuration classes) replacing `BackendApplication`; verify `./gradlew :bootstrap:compileJava` passes
- [x] 4.2 Move the four `*Configuration` classes to `com.mikeflat.gateway.bootstrap.config` and empty them (keep the Javadoc conventions); verify they compile with no imports from removed classes
- [x] 4.3 Rewrite `bootstrap/src/main/resources/application.yaml` per design D-5 (name `gateway`, `SERVER_PORT` default 8080, Redis host/port stub settings, health-only exposure, details never, probes disabled, Redis health disabled); verify `./gradlew :bootstrap:bootRun` starts with no Redis running and `curl -i http://localhost:8080/actuator/health` returns `200` `{"status":"UP"}`
- [x] 4.4 Move and adapt the tests to `com.mikeflat.gateway.bootstrap`: `ArchitectureTest` with `ROOT = "com.mikeflat.gateway"`, `HttpProbe` with `get` only, `ServerPortTest` unchanged in behaviour, `HealthEndpointTest` plus a new case asserting `GET /api/v1/resource` returns `404`; verify `./gradlew build` passes on a host without Docker or Redis, runs all four test classes, and `grep -r clientsim gateway` is empty

## 5. Container image

- [x] 5.1 Update `Dockerfile` per design D-6 (module `COPY` lines, `gateway.jar` paths, entrypoint, healthcheck); verify `docker build -t quota-gateway:local .` succeeds from `/gateway`
- [x] 5.2 Run `docker run -d --name quota-gateway -p 8080:8080 quota-gateway:local` (no Redis); verify `curl -i http://localhost:8080/actuator/health` returns `200` with `"status":"UP"`, `docker inspect --format '{{.State.Health.Status}}' quota-gateway` becomes `healthy`, and `docker exec quota-gateway id -u` is not `0`; then remove the container
- [x] 5.3 Verify the port override in the container: `docker run --rm -e SERVER_PORT=9090 -p 9090:9090 quota-gateway:local` answers `200` on `http://localhost:9090/actuator/health`

## 6. Documentation and environment

- [x] 6.1 Rewrite `.env.example` with `SERVER_PORT=8080`, `SPRING_DATA_REDIS_HOST=localhost`, `SPRING_DATA_REDIS_PORT=6379` and gateway-specific comments (Redis configured but not used yet); verify the variables match `application.yaml`
- [x] 6.2 Rewrite `gateway/README.md` for the quota gateway: purpose (link to `../docs`), requirements (JDK 21 via wrapper), module layout with the layer mapping table from design D-1, `com.mikeflat.gateway.*` packages and the `.gitkeep` convention, dependency rules and the temporary `archunit.properties` setting, where to add beans, the stub OpenAPI spec and generated packages, configuration variables, health behaviour (Redis not part of health yet), build/run/test commands and the Docker commands from tasks 5.1–5.3; verify every command in the README runs as written and it mentions no clientsim, `/api/calls` or call counters

## 7. Final check

- [x] 7.1 From a clean state (`./gradlew clean build`, then `docker build` and `docker run`), confirm every scenario in `specs/gateway-runtime/spec.md` holds, and `git status` shows only `/gateway` (and this change) touched
