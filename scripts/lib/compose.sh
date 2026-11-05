# shellcheck shell=sh
# Shared helper for the /scripts/*.sh scripts. Source it, then call `compose <args>`.
#
# - ROOT is the repository root, resolved from the calling script's location
#   (scripts/<name>.sh), so scripts work from any working directory.
# - `compose` checks that Docker, the compose v2 plugin and the engine are available,
#   then runs `docker compose` against the repository's docker-compose.yml and
#   returns its exit code. POSIX sh only (dash, macOS sh, Git Bash).

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)

docker_unavailable() {
  printf 'error: Docker is not available: %s\n' "$1" >&2
  exit 1
}

compose() {
  command -v docker >/dev/null 2>&1 \
    || docker_unavailable "'docker' command not found"
  docker compose version >/dev/null 2>&1 \
    || docker_unavailable "'docker compose' (compose v2 plugin) not found"
  docker info >/dev/null 2>&1 \
    || docker_unavailable "the Docker engine is not running"

  status=0
  docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" "$@" || status=$?
  if [ "$status" -ne 0 ]; then
    printf 'error: docker compose %s failed (exit %s)\n' "$1" "$status" >&2
  fi
  return "$status"
}
