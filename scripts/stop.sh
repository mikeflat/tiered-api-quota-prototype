#!/bin/sh
# Stop and remove the whole stack (containers and network); built images are kept.
set -eu
. "$(dirname -- "$0")/lib/compose.sh"

compose down --remove-orphans
