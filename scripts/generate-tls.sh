#!/usr/bin/env bash
#
# Generates the TLS material used to encrypt client traffic to NATS:
#
#   infra/tls/ca-cert.pem      private CA certificate (services trust this)
#   infra/tls/ca-key.pem       CA private key
#   infra/tls/server-cert.pem  NATS server certificate, signed by the CA
#   infra/tls/server-key.pem   NATS server private key
#
# These are DEVELOPMENT credentials from a self-signed CA. They are git-ignored and must
# never be reused outside a local environment; a real deployment obtains certificates
# from an internal PKI or cert-manager, with automated rotation.
#
# Requires: openssl (macOS, Linux, WSL, or Git Bash on Windows).
# Usage:    bash scripts/generate-tls.sh [--force]
#
# Implementation note: the distinguished name and extensions are supplied through
# openssl config files rather than the -subj/-addext flags. On Git Bash, MSYS rewrites
# any argument that looks like a Unix path, which turns "/CN=..." into
# "C:/Program Files/Git/CN=..." and breaks certificate generation. Config files avoid
# shell path translation entirely and behave identically on every platform.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TLS_DIR="${SCRIPT_DIR}/../infra/tls"
FORCE="${1:-}"

if ! command -v openssl >/dev/null 2>&1; then
  echo "ERROR: openssl was not found on PATH." >&2
  echo "  macOS/Linux: install it with your package manager." >&2
  echo "  Windows:     run this script from Git Bash or WSL." >&2
  exit 1
fi

mkdir -p "${TLS_DIR}"

if [ -f "${TLS_DIR}/server-cert.pem" ] && [ "${FORCE}" != "--force" ]; then
  echo "TLS material already present in infra/tls (pass --force to regenerate)."
  exit 0
fi

echo "Generating a local development CA and a NATS server certificate..."

# ---------------------------------------------------------------------------
# Certificate authority
# ---------------------------------------------------------------------------
cat > "${TLS_DIR}/ca.cnf" <<'CNF'
[req]
default_md         = sha256
prompt             = no
distinguished_name = dn
x509_extensions    = v3_ca

[dn]
CN = TRAMS Local Development CA
O  = TRAMS
OU = Development

[v3_ca]
basicConstraints     = critical,CA:TRUE,pathlen:0
keyUsage             = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
CNF

openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
  -config "${TLS_DIR}/ca.cnf" \
  -keyout "${TLS_DIR}/ca-key.pem" \
  -out "${TLS_DIR}/ca-cert.pem" \
  >/dev/null 2>&1

# ---------------------------------------------------------------------------
# NATS server certificate
#
# The SANs cover every name a client may legitimately use to reach the broker:
# the Compose service name, the container name, and localhost for a host-side
# client such as the NATS CLI or an integration test.
# ---------------------------------------------------------------------------
cat > "${TLS_DIR}/server.cnf" <<'CNF'
[req]
default_md         = sha256
prompt             = no
distinguished_name = dn

[dn]
CN = nats
O  = TRAMS
OU = Development

[v3_server]
basicConstraints       = CA:FALSE
keyUsage               = critical,digitalSignature,keyEncipherment
extendedKeyUsage       = serverAuth
subjectAltName         = DNS:nats,DNS:trams-nats,DNS:localhost,IP:127.0.0.1
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid,issuer
CNF

openssl req -newkey rsa:2048 -nodes \
  -config "${TLS_DIR}/server.cnf" \
  -keyout "${TLS_DIR}/server-key.pem" \
  -out "${TLS_DIR}/server.csr" \
  >/dev/null 2>&1

openssl x509 -req -sha256 -days 825 \
  -in "${TLS_DIR}/server.csr" \
  -CA "${TLS_DIR}/ca-cert.pem" \
  -CAkey "${TLS_DIR}/ca-key.pem" \
  -CAcreateserial \
  -extfile "${TLS_DIR}/server.cnf" \
  -extensions v3_server \
  -out "${TLS_DIR}/server-cert.pem" \
  >/dev/null 2>&1

# Intermediate artefacts are not needed at runtime.
rm -f "${TLS_DIR}/server.csr" "${TLS_DIR}/ca.cnf" "${TLS_DIR}/server.cnf" "${TLS_DIR}/ca-cert.srl"

# The NATS container runs as a non-root user and only needs read access.
chmod 644 "${TLS_DIR}"/*.pem 2>/dev/null || true

echo
echo "Wrote TLS material to infra/tls:"
ls -1 "${TLS_DIR}" | sed 's/^/  /'
echo
echo -n "Chain of trust: "
openssl verify -CAfile "${TLS_DIR}/ca-cert.pem" "${TLS_DIR}/server-cert.pem"
echo -n "Subject Alternative Names: "
openssl x509 -in "${TLS_DIR}/server-cert.pem" -noout -ext subjectAltName | tail -n 1 | sed 's/^ *//'
