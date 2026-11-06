# syntax=docker/dockerfile:1

# Single stage: Node runs the TypeScript sources directly (type stripping), so
# there is nothing to build and no runtime dependency to install.
FROM node:26-alpine
WORKDIR /app

# package.json is needed at runtime for "type": "module".
COPY package.json ./
COPY src ./src
COPY traffic.json ./

# Gateway address inside the stack (its compose service name) and the shared tenants file, which
# must be mounted at /config/tenants.json; override with -e GATEWAY_URL=... / -e TENANTS_FILE=...
# The traffic shape is /app/traffic.json (baked in; compose mounts the repository copy over it).
ENV NODE_ENV=production \
    GATEWAY_URL=http://gateway:8080 \
    TENANTS_FILE=/config/tenants.json

# Non-root user from the base image (uid 1000).
USER node

# No EXPOSE (listens on no port) and no HEALTHCHECK (nothing to probe; "running" is the health).
# Exec form: node is PID 1 and receives SIGTERM from `docker stop` directly.
CMD ["node", "src/main.ts"]
