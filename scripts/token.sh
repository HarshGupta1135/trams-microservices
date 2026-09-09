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

response=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")

printf '%s' "$response" | "$PY" -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('login failed - is the stack running?', file=sys.stderr); raise SystemExit(1)
if 'accessToken' not in d:
    print('login failed:', d.get('detail', d), file=sys.stderr); raise SystemExit(1)
print('# %s  roles=%s  expires in %ss' % (d['user']['email'], d['user']['roles'], d['expiresIn']), file=sys.stderr)
print(d['accessToken'])
"
