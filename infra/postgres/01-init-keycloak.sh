#!/bin/bash
# Script d'init Postgres exécuté UNE SEULE FOIS au premier démarrage
# (quand le volume postgres_data est vierge). Crée la base et
# l'utilisateur Keycloak avec le mot de passe défini par KC_DB_PASSWORD.
#
# Postgres exécute tous les .sh, .sql et .sql.gz de
# /docker-entrypoint-initdb.d/ dans l'ordre alphabétique.

set -euo pipefail

KC_DB_NAME="${KC_DB_NAME:-keycloak}"
KC_DB_USERNAME="${KC_DB_USERNAME:-keycloak}"

if [[ -z "${KC_DB_PASSWORD:-}" ]]; then
  echo "[init-keycloak] ERROR: KC_DB_PASSWORD not set ; aborting Keycloak DB init." >&2
  exit 1
fi

echo "[init-keycloak] Creating user $KC_DB_USERNAME and database $KC_DB_NAME"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER "${KC_DB_USERNAME}" WITH PASSWORD '${KC_DB_PASSWORD}';
    CREATE DATABASE "${KC_DB_NAME}" OWNER "${KC_DB_USERNAME}";
EOSQL

echo "[init-keycloak] Done."
