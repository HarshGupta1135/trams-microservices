#!/usr/bin/env bash
#
# Creates a ready-to-run `.env` from `.env.example`, generating:
#
#   - an RS256 key pair for JWT signing (base64-encoded PEM)
#   - strong random passwords for Postgres, NATS and Redis
#   - a random internal service key
#
# The result is git-ignored. Nothing in this repository ships with a working
# default credential, which is the point: a checkout is not a set of usable keys.
#
# Requires: openssl. Usage: bash scripts/generate-secrets.sh [--force]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
TEMPLATE="${ROOT_DIR}/.env.example"
FORCE="${1:-}"

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl was not found on PATH." >&2
  exit 1
fi

if [ -f "${ENV_FILE}" ] && [ "${FORCE}" != "--force" ]; then
  echo ".env already exists. Pass --force to overwrite it (this rotates every secret)."
  exit 0
fi

# `openssl base64 -A` is used rather than `base64 -w0`: the -w flag does not
# exist on BSD/macOS base64, whereas openssl behaves identically everywhere.
random_secret() {
  openssl rand -base64 30 | tr -d '/+=\n' | cut -c1-32
}

echo "Generating an RS256 key pair for JWT signing..."
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

# PKCS#8 ("BEGIN PRIVATE KEY") and SubjectPublicKeyInfo ("BEGIN PUBLIC KEY"),
# which are the formats Java's KeyFactory reads directly.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "${TMP_DIR}/jwt-private.pem" 2>/dev/null
openssl rsa -in "${TMP_DIR}/jwt-private.pem" -pubout \
  -out "${TMP_DIR}/jwt-public.pem" 2>/dev/null

JWT_PRIVATE_B64="$(openssl base64 -A -in "${TMP_DIR}/jwt-private.pem")"
JWT_PUBLIC_B64="$(openssl base64 -A -in "${TMP_DIR}/jwt-public.pem")"

echo "Generating random passwords..."
POSTGRES_SUPER_PW="$(random_secret)"
USER_DB_PW="$(random_secret)"
NOTIFICATION_DB_PW="$(random_secret)"
NATS_USER_PW="$(random_secret)"
NATS_NOTIFICATION_PW="$(random_secret)"
REDIS_PW="$(random_secret)"
INTERNAL_KEY="$(random_secret)$(random_secret)"

# Rewrite the template line by line. Using a Python-free, sed-free substitution
# keeps base64 payloads intact - they contain characters that would otherwise
# need escaping in a sed replacement.
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    JWT_PRIVATE_KEY_BASE64=*)      echo "JWT_PRIVATE_KEY_BASE64=${JWT_PRIVATE_B64}" ;;
    JWT_PUBLIC_KEY_BASE64=*)       echo "JWT_PUBLIC_KEY_BASE64=${JWT_PUBLIC_B64}" ;;
    POSTGRES_PASSWORD=*)           echo "POSTGRES_PASSWORD=${POSTGRES_SUPER_PW}" ;;
    USER_DB_PASSWORD=*)            echo "USER_DB_PASSWORD=${USER_DB_PW}" ;;
    NOTIFICATION_DB_PASSWORD=*)    echo "NOTIFICATION_DB_PASSWORD=${NOTIFICATION_DB_PW}" ;;
    NATS_USER_SERVICE_PASSWORD=*)  echo "NATS_USER_SERVICE_PASSWORD=${NATS_USER_PW}" ;;
    NATS_NOTIFICATION_SERVICE_PASSWORD=*)
                                   echo "NATS_NOTIFICATION_SERVICE_PASSWORD=${NATS_NOTIFICATION_PW}" ;;
    REDIS_PASSWORD=*)              echo "REDIS_PASSWORD=${REDIS_PW}" ;;
    INTERNAL_API_KEY=*)            echo "INTERNAL_API_KEY=${INTERNAL_KEY}" ;;
    *)                             echo "$line" ;;
  esac
done < "${TEMPLATE}" > "${ENV_FILE}"

chmod 600 "${ENV_FILE}" 2>/dev/null || true

echo
echo "Wrote ${ENV_FILE} with generated secrets."
echo "  JWT key pair:  RSA 2048, RS256"
echo "  Passwords:     Postgres (x3), NATS (x2), Redis, internal service key"
echo
echo "Next:"
echo "  1. bash scripts/generate-tls.sh    # TLS material for NATS"
echo "  2. docker compose up -d --build"
