#!/usr/bin/env bash
#
# One command to get the whole system running.
#
#   bash scripts/start.sh              start everything
#   bash scripts/start.sh --verify     start, then run the 34-check smoke suite
#   bash scripts/start.sh --rebuild    force a clean rebuild from empty volumes
#
# Generates secrets and TLS material on first run, builds the images, waits for
# every container to report healthy, creates two demo accounts so the API can be
# tried immediately, and prints where to go next.

set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

VERIFY=0
REBUILD=0
for arg in "$@"; do
  case "$arg" in
    --verify)  VERIFY=1 ;;
    --rebuild) REBUILD=1 ;;
    -h|--help) sed -n '3,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 1 ;;
  esac
done

if [ -t 1 ]; then
  G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; D=$'\033[2m'; N=$'\033[0m'
  TTY=1
else
  G=''; R=''; Y=''; B=''; D=''; N=''
  TTY=0
fi

step() { printf '\n%s==>%s %s%s%s\n' "$B" "$N" "$B" "$1" "$N"; }
ok()   { printf '    %s+%s %s\n' "$G" "$N" "$1"; }
warn() { printf '    %s!%s %s\n' "$Y" "$N" "$1"; }
die()  { printf '\n    %sx%s %s\n\n' "$R" "$N" "$1"; exit 1; }

# --- prerequisites ---------------------------------------------------------
step "Checking prerequisites"

command -v docker >/dev/null 2>&1 \
  || die "Docker is not installed. See https://docs.docker.com/get-docker/"

docker info >/dev/null 2>&1 \
  || die "Docker is installed but not running. Start Docker Desktop and try again."
ok "Docker is running"

if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE="docker-compose"
else
  die "Docker Compose is unavailable. Update Docker Desktop or install the compose plugin."
fi
ok "Compose is available"
ok "No local Java or Maven needed - the images build themselves"

# --- configuration ---------------------------------------------------------
step "Preparing configuration"

if [ -f .env ]; then
  ok ".env already present (delete it to regenerate)"
else
  bash scripts/generate-secrets.sh >/dev/null 2>&1 \
    && ok "generated .env - fresh RSA key pair and random passwords" \
    || die "could not generate .env - run scripts/generate-secrets.sh to see why"
fi

if [ -f infra/tls/server-cert.pem ]; then
  ok "TLS material already present"
else
  bash scripts/generate-tls.sh >/dev/null 2>&1 \
    && ok "generated infra/tls - local CA and NATS server certificate" \
    || die "could not generate TLS material - run scripts/generate-tls.sh to see why"
fi

# Read the few values needed here WITHOUT sourcing .env.
#
# `set -a; . ./.env` exports every variable into the environment of the compose
# child process. On Git Bash, MSYS then rewrites Unix-looking absolute paths -
# NATS_TLS_CA_FILE=/etc/nats/tls/ca-cert.pem becomes C:/Program Files/Git/etc/... -
# and Compose prefers an environment value over the .env file, so the services
# start with an unreadable certificate path. Compose parses .env correctly by
# itself; it just must not be shadowed.
env_value() {
  grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d '=' -f 2- | tr -d '\r'
}

GATEWAY_PORT="$(env_value GATEWAY_PORT)"
MAILPIT_PORT="$(env_value MAILPIT_UI_PORT)"
DB_USER="$(env_value USER_DB_USER)"
DB_NAME="$(env_value USER_DB_NAME)"
: "${GATEWAY_PORT:=8080}"
: "${MAILPIT_PORT:=8025}"
BASE_URL="http://localhost:${GATEWAY_PORT}"

# --- build and start -------------------------------------------------------
step "Building and starting containers"

if [ "$REBUILD" = "1" ]; then
  warn "--rebuild: removing existing containers and volumes"
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1
fi

printf '    %sthe first build compiles three Spring Boot apps; expect a few minutes%s\n' "$D" "$N"
if ! $COMPOSE up -d --build >/tmp/trams-start.log 2>&1; then
  tail -25 /tmp/trams-start.log
  die "startup failed - see /tmp/trams-start.log"
fi
ok "containers started"

# --- health ----------------------------------------------------------------
step "Waiting for services to become healthy"

settled=0
for i in $(seq 1 60); do
  states=$($COMPOSE ps --format '{{.Service}}:{{.Health}}' 2>/dev/null)
  pending=$(printf '%s\n' "$states" | grep -c ':starting' || true)
  broken=$(printf '%s\n' "$states" | grep -c ':unhealthy' || true)

  if [ "$pending" -eq 0 ] && [ "$broken" -eq 0 ]; then
    settled=1
    break
  fi
  [ "$TTY" = "1" ] && printf '\r    %swaiting... %ss elapsed, %s still starting%s' \
    "$D" "$((i * 5))" "$pending" "$N"
  sleep 5
done
[ "$TTY" = "1" ] && printf '\r%*s\r' 72 ''

all_healthy=1
while IFS= read -r line; do
  [ -z "$line" ] && continue
  svc="${line%%:*}"
  health="${line##*:}"
  if [ "$health" = "healthy" ]; then
    ok "$svc"
  else
    warn "$svc (${health:-no healthcheck})"
    all_healthy=0
  fi
done <<< "$($COMPOSE ps --format '{{.Service}}:{{.Health}}' 2>/dev/null | sort)"

if [ "$settled" != "1" ] || [ "$all_healthy" != "1" ]; then
  printf '\n    %srecent logs from the services that did not come up:%s\n' "$D" "$N"
  for svc in user-service notification-service api-gateway; do
    health=$($COMPOSE ps --format '{{.Service}}:{{.Health}}' 2>/dev/null \
      | grep "^${svc}:" | cut -d ':' -f 2)
    [ "$health" = "healthy" ] && continue
    printf '\n    --- %s\n' "$svc"
    $COMPOSE logs "$svc" --tail 12 2>&1 | sed 's/^/      /'
  done
  die "services did not become healthy. Full logs: $COMPOSE logs"
fi

# --- demo accounts ---------------------------------------------------------
step "Creating demo accounts"

register() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
    -X POST "${BASE_URL}/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\",\"fullName\":\"$3\"}"
}

code=$(register "demo@trams.local" "demo-password-1234" "Demo User")
case "$code" in
  201) ok "demo@trams.local created" ;;
  409) ok "demo@trams.local already exists" ;;
  *)   warn "demo account request returned HTTP $code" ;;
esac

code=$(register "admin@trams.local" "admin-password-1234" "Admin User")
case "$code" in
  201|409)
    # ADMIN is granted directly in the database on purpose: no endpoint hands out
    # privilege, so it cannot be escalated through the API.
    if $COMPOSE exec -T postgres psql -qtA -U "$DB_USER" -d "$DB_NAME" \
        -c "INSERT INTO user_roles (user_id, role) SELECT id,'ADMIN' FROM users WHERE email='admin@trams.local' ON CONFLICT DO NOTHING;" \
        >/dev/null 2>&1; then
      ok "admin@trams.local created and granted ADMIN"
    else
      warn "admin created, but granting ADMIN failed"
    fi
    ;;
  *) warn "admin account request returned HTTP $code" ;;
esac

# --- optional verification -------------------------------------------------
if [ "$VERIFY" = "1" ]; then
  step "Running the end-to-end smoke suite"
  bash scripts/smoke-test.sh "$BASE_URL"
fi

# --- summary ---------------------------------------------------------------
printf '\n%s%sTRAMS is running%s\n' "$B" "$G" "$N"
printf '
  %sOpen this first%s
    %s/docs        Swagger UI, both services in one selector

  %sLog in with%s
    demo@trams.local   / demo-password-1234    (USER)
    admin@trams.local  / admin-password-1234   (ADMIN, can list users)

    In Swagger: POST /api/v1/auth/login, copy "accessToken", click
    Authorize (top right) and paste it. No "Bearer " prefix needed.
    Or run: bash scripts/token.sh

  %sSee the event pipeline work%s
    Register a user via POST /api/v1/auth/register, then open
    http://localhost:%s - the welcome email arrives there having travelled
    through the transactional outbox and NATS JetStream, with no REST call
    between the two services. GET /api/v1/notifications/me shows the same
    event recorded as a notification.

  %sUseful commands%s
    bash scripts/smoke-test.sh       34 end-to-end checks
    %s logs -f notification-service
    %s ps
    %s down                          stop, keep data
    %s down -v                       stop and wipe data

' "$B" "$N" "$BASE_URL" \
  "$B" "$N" \
  "$B" "$N" "$MAILPIT_PORT" \
  "$B" "$N" "$COMPOSE" "$COMPOSE" "$COMPOSE" "$COMPOSE"
