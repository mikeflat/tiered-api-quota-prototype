#!/bin/sh
# Start only the redis service (other services are left as they are); returns once it is up.
# Does not rebuild an existing image; extra arguments go to compose (e.g. --build).
set -eu
. "$(dirname -- "$0")/lib/compose.sh"

compose up -d --no-deps --wait --wait-timeout 180 "$@" redis
