#!/bin/bash
# Crée le RÔLE PostgreSQL en lecture seule pour Superset. Exécuté UNE SEULE FOIS
# au premier démarrage de Postgres (volume vierge), via docker-entrypoint-initdb.d/.
#
# IMPORTANT : ce script ne fait QUE créer le rôle + GRANT CONNECT. Il n'accorde
# PAS l'accès aux schémas core/analytics ici, car ils n'existent pas encore au
# premier boot (c'est Liquibase, au démarrage d'ingestion-api, qui les crée).
# Les droits SELECT sur core/analytics sont accordés par une migration Liquibase
# dédiée (changeset « superset-readonly-grants »), une fois les schémas créés.
#
# Paramétré par variables d'environnement (cf. docker-compose / .env) :
#   SUPERSET_DB_READONLY_USER     (défaut: superset_ro)
#   SUPERSET_DB_READONLY_PASSWORD (OBLIGATOIRE pour activer la création)
set -euo pipefail

RO_USER="${SUPERSET_DB_READONLY_USER:-superset_ro}"
RO_PASS="${SUPERSET_DB_READONLY_PASSWORD:-}"

if [ -z "$RO_PASS" ]; then
  echo "[init-superset] SUPERSET_DB_READONLY_PASSWORD vide — rôle lecture seule non créé."
  exit 0
fi

echo "[init-superset] Création du rôle lecture seule '$RO_USER' sur la base '$POSTGRES_DB'…"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
-- Rôle de connexion en lecture seule (idempotent). Les GRANT sur les schémas
-- analytiques sont appliqués plus tard par Liquibase (cf. en-tête).
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
