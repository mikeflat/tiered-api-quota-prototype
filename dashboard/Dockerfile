# syntax=docker/dockerfile:1

# ---- Build stage: type-check and bundle with the locked dependencies on Node LTS ----
FROM node:26-alpine AS build
WORKDIR /workspace

# Manifests first, so dependencies are installed in a layer that only changes
# when package.json / package-lock.json change.
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

# Sources last; lint and tests are not run here (they stay in the dev loop / CI).
COPY . .
RUN npm run build

# ---- Runtime stage: unprivileged nginx serving dist/ and proxying to the gateway ----
FROM nginxinc/nginx-unprivileged:1.30-alpine

# The image entrypoint renders /etc/nginx/templates/*.template into
# /etc/nginx/conf.d/ with envsubst at start (replacing the packaged default.conf).
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html

# Gateway host:port and the DNS resolver nginx uses to find it per request.
# 127.0.0.11 is Docker's embedded DNS on user-defined networks (compose).
ENV GATEWAY_UPSTREAM=gateway:8080 \
    NGINX_RESOLVER=127.0.0.11

# Runs as the image's non-root nginx user (uid 101) on an unprivileged port.
USER 101
EXPOSE 8080

# Static check only: the dashboard's health must not depend on the gateway.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
