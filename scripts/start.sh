#!/bin/sh
# Build all images and start the whole stack (Redis, gateway, dashboard, generator).
# Returns once every service is running (healthy where it has a healthcheck), then prints the URLs.
set -eu
. "$(dirname -- "$0")/lib/compose.sh"

compose up -d --build --wait --wait-timeout 180 --remove-orphans

# Effective host ports as published by compose (honours GATEWAY_PORT / DASHBOARD_PORT and .env).
dashboard=$(compose port dashboard 8080)
gateway=$(compose port gateway 8080)
echo ""
echo "Stack is up:"
echo "  dashboard  http://localhost:${dashboard##*:}"
echo "  gateway    http://localhost:${gateway##*:}"
