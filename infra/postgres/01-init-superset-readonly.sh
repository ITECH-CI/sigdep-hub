#!/bin/bash
# Provisionne PostgreSQL pour Superset, au premier démarrage (volume vierge),
# via docker-entrypoint-initdb.d/. Deux choses indépendantes :
#
#  1. Rôle LECTURE SEULE 'superset_ro' (données SIGDEP). On ne fait ici que le
#     créer + GRANT CONNECT : les droits SELECT sur core/analytics sont accordés
#     par une migration Liquibase (les schémas n'existent pas encore à ce stade).
#  2. Base + rôle de MÉTADONNÉES de Superset ('superset_meta'). Superset y stocke
#     son état (dashboards, requêtes SQL Lab…). PostgreSQL au lieu de SQLite :
#     fiable en multi-worker (SQLite échoue sur les écritures concurrentes →
#     « Impossible de migrer l'état de l'éditeur de requêtes »).
#
# Variables (cf. docker-compose / .env) :
#   SUPERSET_DB_READONLY_USER / _PASSWORD  — rôle lecture seule (1)
#   SUPERSET_META_DB / _USER / _PASSWORD   — base + rôle métadonnées (2)
set -euo pipefail

RO_USER="${SUPERSET_DB_READONLY_USER:-superset_ro}"
RO_PASS="${SUPERSET_DB_READONLY_PASSWORD:-}"

META_DB="${SUPERSET_META_DB:-superset_meta}"
META_USER="${SUPERSET_META_USER:-superset_meta}"
META_PASS="${SUPERSET_META_PASSWORD:-}"

# --- 1. Rôle lecture seule (données SIGDEP) --------------------------------
if [ -n "$RO_PASS" ]; then
  echo "[init-superset] Rôle lecture seule '$RO_USER' sur la base '$POSTGRES_DB'…"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${RO_USER}') THEN
    CREATE ROLE ${RO_USER} LOGIN PASSWORD '${RO_PASS}';
  ELSE
    ALTER ROLE ${RO_USER} WITH PASSWORD '${RO_PASS}';
  END IF;
END
\$\$;
GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${RO_USER};
SQL
  echo "[init-superset] Rôle '$RO_USER' créé (droits sur core/analytics via Liquibase)."
else
  echo "[init-superset] SUPERSET_DB_READONLY_PASSWORD vide — rôle lecture seule non créé."
fi

# --- 2. Base + rôle de métadonnées Superset --------------------------------
if [ -n "$META_PASS" ]; then
  echo "[init-superset] Base de métadonnées Superset '$META_DB' (rôle '$META_USER')…"
  # Rôle propriétaire (en écriture) — créé sur la base par défaut.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${META_USER}') THEN
    CREATE ROLE ${META_USER} LOGIN PASSWORD '${META_PASS}';
  ELSE
    ALTER ROLE ${META_USER} WITH PASSWORD '${META_PASS}';
  END IF;
END
\$\$;
SQL
  # CREATE DATABASE ne peut pas être dans un bloc transactionnel/DO → à part,
  # et idempotent via un test préalable.
  if ! psql -tAc "SELECT 1 FROM pg_database WHERE datname='${META_DB}'" \
        --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" | grep -q 1; then
    createdb --username "$POSTGRES_USER" --owner "$META_USER" "$META_DB"
  fi
  echo "[init-superset] Base '$META_DB' prête."
else
  echo "[init-superset] SUPERSET_META_PASSWORD vide — métadonnées sur SQLite (déconseillé en multi-worker)."
fi
