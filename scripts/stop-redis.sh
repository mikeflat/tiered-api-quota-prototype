#!/bin/sh
# Stop only the redis service; the container is kept so start-redis resumes it quickly.
# Extra arguments go to compose (e.g. --timeout 30).
set -eu
. "$(dirname -- "$0")/lib/compose.sh"

compose stop "$@" redis
