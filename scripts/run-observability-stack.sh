#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_ARGS="${COMPOSE_ARGS:---profile load}"
APP_HEALTH_URL="${APP_HEALTH_URL:-http://localhost:8080/actuator/health}"
APP_START_TIMEOUT_SECONDS="${APP_START_TIMEOUT_SECONDS:-180}"
LOADTEST_RUN_ID="${LOADTEST_RUN_ID:-$(date -u +%Y%m%d-%H%M%S)}"
CLEAN_START=1

usage() {
  cat <<'EOF'
Usage:
  ./scripts/run-observability-stack.sh [--no-clean-start] [--help]

Options:
  --no-clean-start  Skip the initial `docker compose down --remove-orphans --volumes`
  --help            Show this help
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-clean-start)
      CLEAN_START=0
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

export LOADTEST_RUN_ID

if [ "$CLEAN_START" = "1" ]; then
  echo "Stopping previous blob-stream stack..."
  # shellcheck disable=SC2086
  docker compose $COMPOSE_ARGS down --remove-orphans --volumes
fi

echo "Starting blob-stream stack with observability and load profile..."
echo "Load test run id: ${LOADTEST_RUN_ID}"
# shellcheck disable=SC2086
docker compose $COMPOSE_ARGS up --build -d

echo "Waiting for application health endpoint: ${APP_HEALTH_URL}"
STARTED_AT="$(date +%s)"
while true; do
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS "$APP_HEALTH_URL" >/dev/null 2>&1; then
      break
    fi
  else
    if docker compose ps app >/dev/null 2>&1; then
      break
    fi
  fi

  NOW="$(date +%s)"
  if [ $((NOW - STARTED_AT)) -ge "$APP_START_TIMEOUT_SECONDS" ]; then
    echo "Application did not become healthy within ${APP_START_TIMEOUT_SECONDS}s."
    echo "Check container status with: docker compose ps"
    echo "Check logs with: docker compose logs --tail=200 app"
    exit 1
  fi
  sleep 3
done

cat <<'EOF'

Stack is up.

Observability pages:
- Grafana:    http://localhost:3000
  Login:      admin / admin
  Dashboard:  http://localhost:3000/d/blob-stream-overview/blob-stream-overview
- Prometheus: http://localhost:9090
- Jaeger:     http://localhost:16686

Service pages:
- App health:   http://localhost:8080/actuator/health
- App metrics:  http://localhost:8080/actuator/prometheus
- MinIO console:http://localhost:9001

Useful commands:
- Follow app logs:      docker compose logs -f app
- Follow load logs:     docker compose logs -f loadtest
- See container status: docker compose ps
- Stop everything:      docker compose down --volumes
- Skip clean restart:   ./scripts/run-observability-stack.sh --no-clean-start

EOF
