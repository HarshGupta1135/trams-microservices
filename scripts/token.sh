#!/usr/bin/env bash
#
# Prints a fresh access token, for pasting into Swagger UI's Authorize dialog.
#
# Access tokens live 15 minutes by design (they are stateless and therefore cannot
# be revoked before expiry), so this exists to avoid retyping a login call.
#
# Usage:
#   bash scripts/token.sh                      # the demo user
#   bash scripts/token.sh admin                # the admin user
#   bash scripts/token.sh you@example.com pw   # any account
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

case "${1:-demo}" in
  demo)  EMAIL="demo@trams.local";  PASSWORD="demo-password-1234" ;;
  admin) EMAIL="admin@trams.local"; PASSWORD="admin-password-1234" ;;
  *)     EMAIL="$1"; PASSWORD="${2:?usage: token.sh <email> <password>}" ;;
esac

PY=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "import json" >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -z "$PY" ] && { echo "python is required" >&2; exit 1; }

# The auth endpoints are rate limited per source IP, and the smoke suite ends by
# deliberately exhausting that bucket. Running this straight afterwards would
# otherwise print an error where a token was expected, and the 401 would show up
# later in Swagger where the cause is far from obvious. So wait it out.
code=""
response=""
for attempt in $(seq 1 20); do
  raw=$(curl -s -w '\n%{http_code}' --max-time 15 -X POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")

  code=$(printf '%s' "$raw" | tail -n 1)
  response=$(printf '%s' "$raw" | sed '$d')

  [ "$code" != "429" ] && break

  if [ "$attempt" = "1" ]; then
    echo "# rate limited - waiting for the token bucket to refill..." >&2
  fi
  sleep 2
done

if [ "$code" = "429" ]; then
  echo "still rate limited after ~40s; wait a moment and try again" >&2
  exit 1
fi

if [ "$code" = "000" ] || [ -z "$code" ]; then
  echo "could not reach ${BASE_URL} - is the stack running? (bash scripts/start.sh)" >&2
  exit 1
fi

printf '%s' "$response" | "$PY" -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('unexpected response from the server (HTTP $code)', file=sys.stderr); raise SystemExit(1)
if 'accessToken' not in d:
    print('login failed (HTTP $code):', d.get('detail', d), file=sys.stderr); raise SystemExit(1)
print('# %s  roles=%s  expires in %ss' % (d['user']['email'], d['user']['roles'], d['expiresIn']), file=sys.stderr)
print(d['accessToken'])
"
