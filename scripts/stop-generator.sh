#!/bin/sh
# Stop only the generator service; the container is kept so start-generator resumes it quickly.
# Extra arguments go to compose (e.g. --timeout 30).
set -eu
. "$(dirname -- "$0")/lib/compose.sh"

compose stop "$@" generator
