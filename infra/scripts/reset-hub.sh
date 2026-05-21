#!/usr/bin/env bash
#
# reset-hub.sh — purge les tables métier (core.*) et l'audit (audit.*)
# du hub SIGDEP, en préservant :
#   - les référentiels (regions, districts, sites, identifier_types)
#   - l'état Liquibase (databasechangelog / databasechangeloglock)
#   - les schémas externes (Keycloak, etc.)
#
# Utile pour repartir d'un hub vide sans devoir tout reconstruire
# (drop database / down -v du volume) — préserve donc Keycloak qui
# vit dans la même instance Postgres.
#
# Usage :
#   ./reset-hub.sh                       # interactif, demande confirmation
#   ./reset-hub.sh --yes                 # bypass confirmation (CI/cron)
#   ./reset-hub.sh --container <name>    # nom du container postgres
#                                        # (défaut: sigdep-postgres)
#   ./reset-hub.sh --db <name>           # nom de la base (défaut: sigdep)
#   ./reset-hub.sh --user <name>         # user postgres (défaut: sigdep)
#
set -euo pipefail

CONTAINER="${SIGDEP_DB_CONTAINER:-sigdep-postgres}"
DB="${SIGDEP_DB_NAME:-sigdep}"
USER="${SIGDEP_DB_USER:-sigdep}"
ASSUME_YES=0

usage() {
    cat <<EOF
TRUNCATE des tables métier core.* et audit.* du hub SIGDEP, en
préservant les référentiels et l'état Liquibase.

Options :
  --yes                    ne demande pas confirmation
  --container <name>       container Postgres (défaut: sigdep-postgres)
  --db <name>              base de données (défaut: sigdep)
  --user <name>            user postgres (défaut: sigdep)
  -h, --help               affiche cette aide

Variables d'environnement :
  SIGDEP_DB_CONTAINER  équivalent --container
  SIGDEP_DB_NAME       équivalent --db
  SIGDEP_DB_USER       équivalent --user

Ce que le script préserve :
  - core.regions / districts / sites / identifier_types (seeds Liquibase)
  - public.databasechangelog* (état Liquibase)
  - Keycloak (s'il partage la même instance Postgres)
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes)         ASSUME_YES=1 ; shift ;;
        --container)   CONTAINER="$2" ; shift 2 ;;
        --db)          DB="$2" ; shift 2 ;;
        --user)        USER="$2" ; shift 2 ;;
        -h|--help)     usage ; exit 0 ;;
        *) echo "Option inconnue: $1" >&2 ; usage >&2 ; exit 1 ;;
    esac
done

# --- Pré-requis -------------------------------------------------------------

if ! command -v docker >/dev/null 2>&1; then
    echo "ERREUR : docker n'est pas dans le PATH." >&2
    exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "ERREUR : container Postgres '$CONTAINER' introuvable ou arrêté." >&2
    echo "  docker ps   pour vérifier" >&2
    exit 1
fi

PSQL="docker exec -i $CONTAINER psql -U $USER -d $DB -v ON_ERROR_STOP=1"

# --- État courant ----------------------------------------------------------

echo "Cible : ${CONTAINER} :: ${DB} (user=${USER})"
echo
echo "État actuel des tables métier :"
$PSQL <<'SQL'
SELECT 'core.patients'                          AS table, count(*) FROM core.patients
UNION ALL SELECT 'core.patient_identifiers',           count(*) FROM core.patient_identifiers
UNION ALL SELECT 'core.visits',                        count(*) FROM core.visits
UNION ALL SELECT 'core.treatment_initiations',         count(*) FROM core.treatment_initiations
UNION ALL SELECT 'core.treatment_initiations_pediatric', count(*) FROM core.treatment_initiations_pediatric
UNION ALL SELECT 'core.closures',                      count(*) FROM core.closures
UNION ALL SELECT 'core.lab_results',                   count(*) FROM core.lab_results
UNION ALL SELECT 'core.tpt_records',                   count(*) FROM core.tpt_records
UNION ALL SELECT 'core.dispensations',                 count(*) FROM core.dispensations
UNION ALL SELECT 'core.screenings',                    count(*) FROM core.screenings
UNION ALL SELECT 'core.ptme_mothers',                  count(*) FROM core.ptme_mothers
UNION ALL SELECT 'core.ptme_mother_visits',            count(*) FROM core.ptme_mother_visits
UNION ALL SELECT 'core.ptme_children',                 count(*) FROM core.ptme_children
UNION ALL SELECT 'core.ptme_child_visits',             count(*) FROM core.ptme_child_visits
UNION ALL SELECT 'audit.sync_batch',                   count(*) FROM audit.sync_batch
UNION ALL SELECT 'audit.rejected_record',              count(*) FROM audit.rejected_record
UNION ALL SELECT '--- preserves ---',                  NULL
UNION ALL SELECT 'core.sites',                         count(*) FROM core.sites
UNION ALL SELECT 'core.regions',                       count(*) FROM core.regions
UNION ALL SELECT 'core.districts',                     count(*) FROM core.districts
UNION ALL SELECT 'core.identifier_types',              count(*) FROM core.identifier_types;
SQL
echo

# --- Confirmation ----------------------------------------------------------

if [[ $ASSUME_YES -ne 1 ]]; then
    echo "Ce script va TRUNCATE les tables métier listées ci-dessus."
    echo "Les référentiels (regions/districts/sites/identifier_types) seront PRÉSERVÉS."
    echo "L'état Liquibase et Keycloak ne sont pas touchés."
    echo
    echo "Les agents en cours vont continuer à pousser des données ; il est"
    echo "préférable de coordonner avec les sites avant un reset en prod."
    echo
    read -r -p "Confirmer ? [y/N] " ans
    case "$ans" in
        y|Y|yes|YES) ;;
        *) echo "Annulé." ; exit 1 ;;
    esac
fi

# --- Exécution -------------------------------------------------------------

echo "TRUNCATE en cours..."
$PSQL <<'SQL'
BEGIN;
TRUNCATE TABLE
    audit.rejected_record,
    audit.sync_batch,
    core.tpt_records,
    core.lab_results,
    core.closures,
    core.dispensations,
    core.treatment_initiations_pediatric,
    core.treatment_initiations,
    core.screenings,
    core.ptme_child_visits,
    core.ptme_children,
    core.ptme_mother_visits,
    core.ptme_mothers,
    core.visits,
    core.patient_identifiers,
    core.patients
RESTART IDENTITY CASCADE;
COMMIT;
SQL

echo "Fait. État après reset :"
$PSQL <<'SQL'
SELECT 'core.patients'                          AS table, count(*) FROM core.patients
UNION ALL SELECT 'core.visits',                        count(*) FROM core.visits
UNION ALL SELECT 'core.treatment_initiations',         count(*) FROM core.treatment_initiations
UNION ALL SELECT 'core.closures',                      count(*) FROM core.closures
UNION ALL SELECT 'core.screenings',                    count(*) FROM core.screenings
UNION ALL SELECT 'core.ptme_mothers',                  count(*) FROM core.ptme_mothers
UNION ALL SELECT 'audit.sync_batch',                   count(*) FROM audit.sync_batch
UNION ALL SELECT '--- preserves ---',                  NULL
UNION ALL SELECT 'core.sites',                         count(*) FROM core.sites
UNION ALL SELECT 'core.identifier_types',              count(*) FROM core.identifier_types;
SQL

echo
echo "OK. Les agents repousseront leurs données au prochain cycle."
echo "Pour forcer un agent à re-extraire depuis le début (et pas seulement"
echo "depuis sa dernière watermark), lance reset-agent.sh côté terrain."
