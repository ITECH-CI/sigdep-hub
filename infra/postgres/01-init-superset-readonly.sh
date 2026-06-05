#!/bin/bash
# Crée un rôle PostgreSQL en LECTURE SEULE pour Superset, et lui accorde
# l'accès SELECT aux schémas analytiques de la base SIGDEP. Exécuté UNE SEULE
# FOIS au premier démarrage de Postgres (volume de données vierge), via
# /docker-entrypoint-initdb.d/.
#
# Paramétré par variables d'environnement (cf. docker-compose / .env) :
#   SUPERSET_DB_READONLY_USER     (défaut: superset_ro)
#   SUPERSET_DB_READONLY_PASSWORD (OBLIGATOIRE pour activer la création)
#
# Si le mot de passe est vide, le script ne fait rien (Superset pourra être
# connecté manuellement plus tard).
set -euo pipefail

RO_USER="${SUPERSET_DB_READONLY_USER:-superset_ro}"
RO_PASS="${SUPERSET_DB_READONLY_PASSWORD:-}"

if [ -z "$RO_PASS" ]; then
  echo "[init-superset] SUPERSET_DB_READONLY_PASSWORD vide — rôle lecture seule non créé."
  exit 0
fi

echo "[init-superset] Création du rôle lecture seule '$RO_USER' sur la base '$POSTGRES_DB'…"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
-- Rôle de connexion en lecture seule (idempotent).
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${RO_USER}') THEN
    CREATE ROLE ${RO_USER} LOGIN PASSWORD '${RO_PASS}';
  ELSE
    ALTER ROLE ${RO_USER} WITH PASSWORD '${RO_PASS}';
  END IF;
END
\$\$;

-- Accès en lecture aux schémas analytiques (PAS au schéma auth : Superset
-- ne doit jamais lire les hash de mots de passe ni les clés API).
GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${RO_USER};
GRANT USAGE ON SCHEMA core, analytics TO ${RO_USER};
GRANT SELECT ON ALL TABLES IN SCHEMA core, analytics TO ${RO_USER};

-- Les tables créées plus tard (nouvelles migrations) seront aussi lisibles.
ALTER DEFAULT PRIVILEGES IN SCHEMA core      GRANT SELECT ON TABLES TO ${RO_USER};
ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO ${RO_USER};
SQL

echo "[init-superset] Rôle '$RO_USER' prêt (SELECT sur core + analytics, schéma auth exclu)."
