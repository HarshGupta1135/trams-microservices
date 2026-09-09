#!/usr/bin/env bash
#
# End-to-end check against a running stack.
#
# Exercises the whole system through the gateway only - the same surface a real
# client has - and asserts the behaviours that matter: authentication, the
# asynchronous event pipeline, token rotation with theft detection, rate
# limiting, and gateway-only admission.
#
# Usage:  bash scripts/smoke-test.sh [base-url]
# Requires: curl, python3 (or python). Exits non-zero on the first failure.

set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"

PASSED=0
FAILED=0

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else
  GREEN=''; RED=''; DIM=''; BOLD=''; RESET=''
fi

section() { printf '\n%s%s%s\n' "$BOLD" "$1" "$RESET"; }

pass() { PASSED=$((PASSED + 1)); printf '  %sPASS%s %s\n' "$GREEN" "$RESET" "$1"; }

fail() {
  FAILED=$((FAILED + 1))
  printf '  %sFAIL%s %s\n' "$RED" "$RESET" "$1"
  [ -n "${2:-}" ] && printf '       %s%s%s\n' "$DIM" "$2" "$RESET"
}

expect_status() {
  local description="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    pass "$description (HTTP $actual)"
  else
    fail "$description" "expected HTTP $expected, got $actual"
  fi
}

# Locate a working interpreter by actually running it. Checking only that the
# name exists on PATH is not enough on Windows, where `python3` is often an App
# Execution Alias stub that exists but fails, printing "Python was not found".
PY=""
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import json,sys" >/dev/null 2>&1; then
    PY="$candidate"
    break
  fi
done

if [ -z "$PY" ]; then
  echo "ERROR: a working python3/python is required to parse JSON responses." >&2
  exit 1
fi

json_field() { printf '%s' "$1" | "$PY" -c "
import sys, json
try:
    data = json.load(sys.stdin)
except Exception:
    print(''); raise SystemExit
for key in '$2'.split('.'):
    if isinstance(data, dict):
        data = data.get(key)
    else:
        data = None
        break
print('' if data is None else data)
"; }

# The auth endpoints are rate limited per source IP, and section 10 deliberately
# exhausts that bucket. Waiting for it to refill makes the suite re-runnable
# back-to-back: without this, a second run inside the refill window fails on
# register/login and looks like a broken system rather than a drained bucket.
wait_for_auth_capacity() {
  # One probe to see whether the bucket is saturated at all.
  probe=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/login"     -H 'Content-Type: application/json'     -d '{"email":"capacity-probe@example.com","password":"a-probe-password-value"}')

  if [ "$probe" = "429" ]; then
    printf '  %swaiting for the auth rate limiter to refill...%s
' "$DIM" "$RESET"
    for _ in $(seq 1 30); do
      sleep 1
      probe=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/login"         -H 'Content-Type: application/json'         -d '{"email":"capacity-probe@example.com","password":"a-probe-password-value"}')
      [ "$probe" != "429" ] && break
    done
  fi

  # A single free token is not enough: the sections below make several auth calls
  # in quick succession, and each probe above consumed one. Pause long enough for
  # a full burst to accumulate (burst capacity / replenish rate, plus headroom).
  sleep "${AUTH_REFILL_SECONDS:-4}"
}

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
STAMP="$(date +%s)-$$"
EMAIL="smoke.${STAMP}@example.com"
PASSWORD="a-sufficiently-long-password"
NEW_PASSWORD="an-even-longer-replacement-password"
CORRELATION_ID="smoke-${STAMP}"

printf '%sTRAMS smoke test%s\n' "$BOLD" "$RESET"
printf '  gateway : %s\n  account : %s\n' "$BASE_URL" "$EMAIL"

# ---------------------------------------------------------------------------
section "1. Availability"
# ---------------------------------------------------------------------------
status=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/actuator/health/readiness")
expect_status "gateway reports ready" 200 "$status"

# ---------------------------------------------------------------------------
section "2. Authentication and authorisation"
# ---------------------------------------------------------------------------
wait_for_auth_capacity

status=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/api/v1/users/me")
expect_status "protected route rejects an anonymous caller" 401 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer not-a-real-token' \
  "${BASE_URL}/api/v1/users/me")
expect_status "protected route rejects a forged token" 401 "$status"

response=$(curl -s -w '\n%{http_code}' -X POST "${BASE_URL}/api/v1/auth/register" \
  -H 'Content-Type: application/json' -H "X-Correlation-Id: ${CORRELATION_ID}" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"fullName\":\"Smoke Test\"}")
status=$(printf '%s' "$response" | tail -n1)
body=$(printf '%s' "$response" | sed '$d')
expect_status "register a new account" 201 "$status"
USER_ID=$(json_field "$body" "id")

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"fullName\":\"Duplicate\"}")
expect_status "duplicate registration is refused" 409 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"email":"not-an-email","password":"short","fullName":""}')
expect_status "invalid payload is rejected with validation errors" 400 "$status"

login=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
ACCESS_TOKEN=$(json_field "$login" "accessToken")
REFRESH_TOKEN=$(json_field "$login" "refreshToken")

if [ -n "$ACCESS_TOKEN" ]; then pass "login returns an access token"; else fail "login returns an access token" "$login"; fi
if [ -n "$REFRESH_TOKEN" ]; then pass "login returns a refresh token"; else fail "login returns a refresh token"; fi

me=$(curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/users/me")
if [ "$(json_field "$me" "email")" = "$EMAIL" ]; then
  pass "the access token authenticates the right user"
else
  fail "the access token authenticates the right user" "$me"
fi

status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "${BASE_URL}/api/v1/users")
expect_status "admin-only route refuses a plain user" 403 "$status"

# ---------------------------------------------------------------------------
section "3. Asynchronous event pipeline (no REST between services)"
# ---------------------------------------------------------------------------
printf '  %swaiting for the registration event to be consumed...%s\n' "$DIM" "$RESET"
notification_count=0
for _ in $(seq 1 20); do
  history=$(curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/notifications/me")
  notification_count=$(json_field "$history" "totalElements")
  [ "${notification_count:-0}" -ge 1 ] 2>/dev/null && break
  sleep 1
done

if [ "${notification_count:-0}" -ge 1 ]; then
  pass "the Notification Service produced a notification from the event"
else
  fail "the Notification Service produced a notification from the event" \
       "no notification appeared within 20s - check 'docker compose logs notification-service'"
fi

welcome_status=$(printf '%s' "$history" | "$PY" -c "
import sys, json
d = json.load(sys.stdin)
for n in d.get('content', []):
    if n.get('eventType') == 'user.registered':
        print(n.get('status')); break
else:
    print('MISSING')
")
if [ "$welcome_status" = "SENT" ]; then
  pass "the welcome notification was delivered (status SENT)"
else
  fail "the welcome notification was delivered" "status was: $welcome_status"
fi

# ---------------------------------------------------------------------------
section "4. Token rotation and theft detection"
# ---------------------------------------------------------------------------
rotated=$(curl -s -X POST "${BASE_URL}/api/v1/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"${REFRESH_TOKEN}\"}")
ROTATED_TOKEN=$(json_field "$rotated" "refreshToken")

if [ -n "$ROTATED_TOKEN" ] && [ "$ROTATED_TOKEN" != "$REFRESH_TOKEN" ]; then
  pass "refreshing rotates the token"
else
  fail "refreshing rotates the token" "$rotated"
fi

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"${REFRESH_TOKEN}\"}")
expect_status "replaying a consumed refresh token is refused" 401 "$status"

# The replay is treated as theft, so the entire family must be revoked - including
# the token that was legitimately issued by the rotation above.
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"${ROTATED_TOKEN}\"}")
expect_status "detecting replay revokes the whole token family" 401 "$status"

# ---------------------------------------------------------------------------
section "5. Profile changes emit further events"
# ---------------------------------------------------------------------------
wait_for_auth_capacity

login=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
ACCESS_TOKEN=$(json_field "$login" "accessToken")

status=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "${BASE_URL}/api/v1/users/me" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" -H 'Content-Type: application/json' \
  -d '{"fullName":"Smoke Test Renamed"}')
expect_status "update the profile" 200 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/users/me/change-password" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"currentPassword\":\"${PASSWORD}\",\"newPassword\":\"${NEW_PASSWORD}\"}")
expect_status "change the password" 204 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
expect_status "the old password no longer works" 401 "$status"

login=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${NEW_PASSWORD}\"}")
ACCESS_TOKEN=$(json_field "$login" "accessToken")

printf '  %swaiting for the profile and password events...%s\n' "$DIM" "$RESET"
for _ in $(seq 1 20); do
  history=$(curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/notifications/me")
  total=$(json_field "$history" "totalElements")
  [ "${total:-0}" -ge 3 ] 2>/dev/null && break
  sleep 1
done

if [ "${total:-0}" -ge 3 ]; then
  pass "all three event types produced notifications (registered, profile, password)"
else
  fail "all three event types produced notifications" "only ${total:-0} notification(s) found"
fi

# ---------------------------------------------------------------------------
section "6. Correlation id handling"
# ---------------------------------------------------------------------------
correlation=$(curl -s -D - -o /dev/null -H 'X-Correlation-Id: smoke-trace-check' \
  "${BASE_URL}/actuator/health" | grep -i '^x-correlation-id:' | tr -d '\r' | awk '{print $2}')
if [ "$correlation" = "smoke-trace-check" ]; then
  pass "a valid correlation id is honoured and echoed back"
else
  fail "a valid correlation id is honoured" "got: '$correlation'"
fi

correlation=$(curl -s -D - -o /dev/null -H 'X-Correlation-Id: not a valid id' \
  "${BASE_URL}/actuator/health" | grep -i '^x-correlation-id:' | tr -d '\r' | awk '{print $2}')
if [ -n "$correlation" ] && [ "$correlation" != "not" ]; then
  pass "a malformed correlation id is replaced, never reflected"
else
  fail "a malformed correlation id is replaced" "got: '$correlation'"
fi


# ---------------------------------------------------------------------------
section "7. HTTP error mapping"
#
# Regression cover. These all returned 500 at one point, because a catch-all
# @ExceptionHandler(Exception.class) was intercepting failures that Spring MVC
# maps correctly on its own. A 500 means "this service is broken" and should
# wake someone; a bad query parameter should not.
# ---------------------------------------------------------------------------
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${ACCESS_TOKEN}"   "${BASE_URL}/api/v1/notifications/me?status=NOT_A_STATUS")
expect_status "an unparseable enum in a query string is a client error" 400 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${ACCESS_TOKEN}"   "${BASE_URL}/api/v1/notifications/me/not-a-uuid")
expect_status "a malformed path variable is a client error" 400 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/register"   -H 'Content-Type: text/plain' -d 'not json')
expect_status "an unsupported Content-Type is rejected as such" 415 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${BASE_URL}/api/v1/auth/login")
expect_status "a wrong HTTP method is rejected as such" 405 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH "${BASE_URL}/api/v1/users/me"   -H "Authorization: Bearer ${ACCESS_TOKEN}" -H 'Content-Type: application/json' -d '{not json')
expect_status "malformed JSON is a client error" 400 "$status"

# Every error, whoever produced it, carries the same extension members.
body=$(curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" "${BASE_URL}/api/v1/notifications/me?status=NOT_A_STATUS")
if [ -n "$(json_field "$body" "code")" ] && [ -n "$(json_field "$body" "correlationId")" ]; then
  pass "framework errors carry code and correlationId, like domain errors"
else
  fail "framework errors carry code and correlationId" "$body"
fi

served=$(json_field "$(curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}"   "${BASE_URL}/api/v1/notifications/me?size=100000")" "size")
if [ "${served:-0}" -le 100 ] 2>/dev/null; then
  pass "an absurd page size is capped (asked 100000, served ${served})"
else
  fail "an absurd page size is capped" "served size: ${served}"
fi

# ---------------------------------------------------------------------------
section "8. Documentation endpoints"
#
# Regression cover. /docs returned 404 at one point because api-docs was
# disabled on the gateway, which also removes the swagger-config endpoint the
# UI bootstraps from. A documented URL that nobody ever opens is a broken one.
# ---------------------------------------------------------------------------
status=$(curl -s -o /dev/null -w '%{http_code}' -L "${BASE_URL}/docs")
expect_status "Swagger UI is served at /docs" 200 "$status"

status=$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/v3/api-docs/swagger-config")
expect_status "the UI can fetch its bootstrap config" 200 "$status"

for spec in user-service notification-service; do
  body=$(curl -s "${BASE_URL}/api-docs/${spec}")
  if [ -n "$(json_field "$body" "openapi")" ]; then
    pass "the ${spec} OpenAPI document is reachable and valid"
  else
    fail "the ${spec} OpenAPI document is reachable" "got: $(printf '%s' "$body" | head -c 120)"
  fi
done

# ---------------------------------------------------------------------------
section "9. Delivered mail (Mailpit)"
# ---------------------------------------------------------------------------
captured=$(curl -s --max-time 5 "${MAILPIT_URL}/api/v1/messages?limit=50" 2>/dev/null | "$PY" -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(-1); raise SystemExit
print(sum(1 for m in d.get('messages', []) if '$EMAIL' in json.dumps(m.get('To', []))))
" 2>/dev/null || echo -1)

if [ "${captured:-0}" -ge 1 ] 2>/dev/null; then
  pass "Mailpit captured ${captured} message(s) for this account"
elif [ "${captured}" = "-1" ]; then
  printf '  %sSKIP%s Mailpit is not reachable at %s\n' "$DIM" "$RESET" "$MAILPIT_URL"
else
  fail "Mailpit captured mail for this account" "found none - is the channel set to LOG?"
fi

# ---------------------------------------------------------------------------
section "10. Rate limiting"
#
# Deliberately the last check. It exhausts the auth endpoint's token bucket, and
# the bucket refills over several seconds - so any assertion running after it
# against /api/v1/auth/** sees 429 instead of the status it meant to verify.
# Placed mid-suite this made the run order-dependent and intermittently red.
# ---------------------------------------------------------------------------
printf '  %shammering the auth endpoint to trip the rate limiter...%s\n' "$DIM" "$RESET"
rate_limited=0
for _ in $(seq 1 25); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d '{"email":"nobody@example.com","password":"wrong-password-value"}')
  [ "$code" = "429" ] && rate_limited=1 && break
done
if [ "$rate_limited" = "1" ]; then
  pass "the auth endpoint is rate limited"
else
  fail "the auth endpoint is rate limited" "no 429 within 25 rapid attempts"
fi

# ---------------------------------------------------------------------------
section "Summary"
# ---------------------------------------------------------------------------
printf '  %s%d passed%s, %s%d failed%s\n' "$GREEN" "$PASSED" "$RESET" \
  "$([ "$FAILED" -gt 0 ] && printf '%s' "$RED" || printf '%s' "$DIM")" "$FAILED" "$RESET"
[ -n "${USER_ID:-}" ] && printf '  %saccount id: %s%s\n' "$DIM" "$USER_ID" "$RESET"
printf '  %sinspect delivered mail at %s%s\n\n' "$DIM" "$MAILPIT_URL" "$RESET"

[ "$FAILED" -eq 0 ] || exit 1
