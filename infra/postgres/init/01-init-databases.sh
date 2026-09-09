#!/bin/bash
#
# Creates one database per service, each owned by its own least-privilege role.
#
# Database-per-service is a hard boundary in this system, not a convention: the
# Notification Service physically cannot read the users table, so the only way the two
# services can share information is the event contract. That is what stops the classic
# slide into a shared database, where an innocent-looking JOIN quietly couples two
# services forever.
#
# CONNECT is revoked from PUBLIC on each database, so a role can only reach the database
# it owns even though both live in the same cluster.
#
# Runs once, on first initialisation of the Postgres data volume.

set -euo pipefail

required_var() {
  if [ -z "${!1:-}" ]; then
    echo "FATAL: environment variable $1 is required for database initialisation" >&2
    exit 1
  fi
}

for var in USER_DB_NAME USER_DB_USER USER_DB_PASSWORD \
           NOTIFICATION_DB_NAME NOTIFICATION_DB_USER NOTIFICATION_DB_PASSWORD; do
  required_var "$var"
done

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    -- ---------------------------------------------------------------------
    -- User Service
    -- ---------------------------------------------------------------------
    CREATE ROLE "${USER_DB_USER}" WITH LOGIN PASSWORD '${USER_DB_PASSWORD}';
    CREATE DATABASE "${USER_DB_NAME}" OWNER "${USER_DB_USER}" ENCODING 'UTF8';

    REVOKE ALL ON DATABASE "${USER_DB_NAME}" FROM PUBLIC;
    GRANT CONNECT ON DATABASE "${USER_DB_NAME}" TO "${USER_DB_USER}";

    -- ---------------------------------------------------------------------
    -- Notification Service
    -- ---------------------------------------------------------------------
    CREATE ROLE "${NOTIFICATION_DB_USER}" WITH LOGIN PASSWORD '${NOTIFICATION_DB_PASSWORD}';
    CREATE DATABASE "${NOTIFICATION_DB_NAME}" OWNER "${NOTIFICATION_DB_USER}" ENCODING 'UTF8';

    REVOKE ALL ON DATABASE "${NOTIFICATION_DB_NAME}" FROM PUBLIC;
    GRANT CONNECT ON DATABASE "${NOTIFICATION_DB_NAME}" TO "${NOTIFICATION_DB_USER}";
SQL

# Lock down the public schema in each database. Since PostgreSQL 15 the public schema is
# no longer world-writable, but making the owner explicit keeps intent unambiguous.
for db_pair in "${USER_DB_NAME}:${USER_DB_USER}" "${NOTIFICATION_DB_NAME}:${NOTIFICATION_DB_USER}"; do
  db_name="${db_pair%%:*}"
  db_owner="${db_pair##*:}"

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db_name" <<-SQL
      ALTER SCHEMA public OWNER TO "${db_owner}";
      REVOKE ALL ON SCHEMA public FROM PUBLIC;
      GRANT ALL ON SCHEMA public TO "${db_owner}";
SQL
done

echo "Initialised databases: ${USER_DB_NAME} (owner ${USER_DB_USER}), ${NOTIFICATION_DB_NAME} (owner ${NOTIFICATION_DB_USER})"
