# syntax=docker/dockerfile:1

# ---- Build stage: compile with the committed Gradle wrapper on JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Wrapper and build scripts first, so the Gradle distribution and plugins are fetched in a
# layer that only changes when the build definition changes.
COPY gradlew ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./
COPY domain/build.gradle.kts domain/
COPY app/build.gradle.kts app/
COPY adapters/api/build.gradle.kts adapters/api/
COPY adapters/infra/build.gradle.kts adapters/infra/
COPY adapters/persistence/build.gradle.kts adapters/persistence/
COPY bootstrap/build.gradle.kts bootstrap/

# A CRLF checkout on Windows would break the wrapper script.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon help

# Sources last; tests are not run here (they stay in `./gradlew build` on the host/CI).
COPY . .
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :bootstrap:bootJar \
    && cp bootstrap/build/libs/gateway.jar /workspace/gateway.jar

# ---- Runtime stage: JRE 21, non-root, only the executable gateway jar ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/gateway.jar /app/gateway.jar
USER app
ENV SERVER_PORT=8080
# The tenants file is not part of the image: mount the repository's config/tenants.json here
# (docker compose does). Without it the gateway refuses to start.
ENV TENANTS_FILE=/config/tenants.json
EXPOSE 8080
# Shell-form check, so it follows a SERVER_PORT override given at `docker run`. Liveness, not
# /actuator/health, so a Redis outage never marks the gateway container unhealthy.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health/liveness" || exit 1
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
