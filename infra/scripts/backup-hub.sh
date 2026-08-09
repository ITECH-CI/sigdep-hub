#!/usr/bin/env bash
#
# backup-hub.sh — sauvegarde la base Postgres du hub SIGDEP dans un dump
# compressé horodaté, avec rotation (rétention en jours).
#
# Deux périmètres :
#   --scope sigdep   (défaut) : la base `sigdep` seule (core.*, audit.*, auth.*
#                    — données métier, rejets, comptes et clés API). NE contient
#                    PAS les dashboards Superset (base superset_meta).
#   --scope full     : pg_dumpall — TOUTE l'instance : sigdep + superset_meta
#                    (dashboards/datasets Superset) + les rôles Postgres. À
#                    utiliser pour une restauration à l'identique / un
#                    déplacement de serveur sans rien perdre.
#
# Le dump est écrit dans $BACKUP_DIR (défaut /var/backups/sigdep), nommé
#   sigdep-<scope>-YYYY-MM-DD_HHMMSS.sql.gz
# Les dumps plus vieux que --retention jours (défaut 30) sont supprimés.
#
# Usage :
#   ./backup-hub.sh                          # scope sigdep, rétention 30j
#   ./backup-hub.sh --scope full             # tout (Superset inclus)
#   ./backup-hub.sh --dir /mnt/backups       # autre répertoire
#   ./backup-hub.sh --retention 60           # garder 60 jours
#   ./backup-hub.sh --container sigdep-postgres --user sigdep --db sigdep
#
# Variables d'environnement équivalentes :
#   SIGDEP_DB_CONTAINER, SIGDEP_DB_NAME, SIGDEP_DB_USER,
#   SIGDEP_BACKUP_DIR, SIGDEP_BACKUP_RETENTION_DAYS, SIGDEP_BACKUP_SCOPE
#
# Cron (dump complet quotidien à 02h30, log dédié) :
#   30 2 * * *  /opt/sigdep-hub/infra/scripts/backup-hub.sh --scope full \
#               >> /var/log/sigdep-backup.log 2>&1
#
set -euo pipefail

CONTAINER="${SIGDEP_DB_CONTAINER:-sigdep-postgres}"
DB="${SIGDEP_DB_NAME:-sigdep}"
USER="${SIGDEP_DB_USER:-sigdep}"
BACKUP_DIR="${SIGDEP_BACKUP_DIR:-/var/backups/sigdep}"
RETENTION_DAYS="${SIGDEP_BACKUP_RETENTION_DAYS:-30}"
SCOPE="${SIGDEP_BACKUP_SCOPE:-sigdep}"

usage() {
    cat <<EOF
Sauvegarde la base Postgres du hub SIGDEP (dump compressé horodaté + rotation).

Options :
  --scope <sigdep|full>    périmètre du dump (défaut: sigdep)
                           sigdep = base sigdep seule (métier + comptes)
                           full   = instance entière (Superset + rôles inclus)
  --dir <path>             répertoire des dumps (défaut: /var/backups/sigdep)
  --retention <jours>      supprime les dumps plus vieux (défaut: 30)
  --container <name>       container Postgres (défaut: sigdep-postgres)
  --db <name>              base de données (défaut: sigdep)
  --user <name>            user postgres (défaut: sigdep)
  -h, --help               affiche cette aide

Restauration : voir docs/DEPLOYMENT.md (section « Disaster recovery »).
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scope)      SCOPE="$2" ; shift 2 ;;
        --dir)        BACKUP_DIR="$2" ; shift 2 ;;
        --retention)  RETENTION_DAYS="$2" ; shift 2 ;;
        --container)  CONTAINER="$2" ; shift 2 ;;
        --db)         DB="$2" ; shift 2 ;;
        --user)       USER="$2" ; shift 2 ;;
        -h|--help)    usage ; exit 0 ;;
        *) echo "Option inconnue: $1" >&2 ; usage >&2 ; exit 1 ;;
    esac
done

if [[ "$SCOPE" != "sigdep" && "$SCOPE" != "full" ]]; then
    echo "ERREUR : --scope doit être 'sigdep' ou 'full' (reçu: '$SCOPE')." >&2
    exit 1
fi

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

mkdir -p "$BACKUP_DIR"

# Horodatage sans dépendance à la locale (tri lexicographique = tri temporel).
STAMP="$(date +%Y-%m-%d_%H%M%S)"
OUT="${BACKUP_DIR}/sigdep-${SCOPE}-${STAMP}.sql.gz"
TMP="${OUT}.part"

echo "[$(date +%F' '%T)] Backup SIGDEP — scope=${SCOPE} container=${CONTAINER}"
echo "  → ${OUT}"

# --- Dump -------------------------------------------------------------------
# On écrit d'abord dans un fichier .part, renommé seulement en cas de succès :
# un dump interrompu ne laisse jamais un .sql.gz d'apparence valide.
#
# set -o pipefail (déjà actif) : si pg_dump échoue, le pipe échoue même si gzip
# réussit — on ne publie pas un dump tronqué.

if [[ "$SCOPE" == "full" ]]; then
    # pg_dumpall : toutes les bases + rôles/utilisateurs Postgres.
    docker exec "$CONTAINER" pg_dumpall -U "$USER" \
        | gzip > "$TMP"
else
    # pg_dump d'une base : --clean --if-exists pour un restore ré-applicable,
    # inclut TOUS les schémas de la base (core, audit, auth, public).
    docker exec "$CONTAINER" pg_dump -U "$USER" --clean --if-exists "$DB" \
        | gzip > "$TMP"
fi

# --- Vérification -----------------------------------------------------------
# Premier garde-fou déjà assuré par `set -o pipefail` : si pg_dump/pg_dumpall
# échoue, le pipe échoue (exit ≠ 0) avant même d'arriver ici, et le .part n'est
# jamais promu. Les checks ci-dessous attrapent un dump tronqué/corrompu qui
# aurait tout de même produit un fichier.

# Intégrité gzip (garde-fou principal : un flux tronqué est détecté ici).
if ! gzip -t "$TMP" 2>/dev/null; then
    echo "ERREUR : le dump gzip est corrompu ou tronqué." >&2
    rm -f "$TMP"
    exit 1
fi

# Taille plancher : un dump vraiment vide (échec silencieux) fait quelques
# dizaines d'octets. Seuil bas (200 o) pour ne PAS rejeter un petit dump
# légitime (hub fraîchement initialisé, base quasi vide).
SIZE_BYTES="$(wc -c < "$TMP" | tr -d ' ')"
if [[ "$SIZE_BYTES" -lt 200 ]]; then
    echo "ERREUR : dump anormalement petit (${SIZE_BYTES} octets) — échec probable." >&2
    rm -f "$TMP"
    exit 1
fi

mv "$TMP" "$OUT"
HUMAN_SIZE="$(du -h "$OUT" | cut -f1)"
echo "  OK — ${HUMAN_SIZE}"

# --- Rotation ---------------------------------------------------------------
# Supprime les dumps du MÊME scope plus vieux que la rétention. On ne touche
# pas aux dumps d'un autre scope (sigdep vs full gérés indépendamment).
if [[ "$RETENTION_DAYS" -gt 0 ]]; then
    DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f \
        -name "sigdep-${SCOPE}-*.sql.gz" \
        -mtime +"$RETENTION_DAYS" -print -delete | wc -l | tr -d ' ')"
    echo "  Rotation : ${DELETED} dump(s) > ${RETENTION_DAYS}j supprimé(s)."
fi

# --- Récapitulatif ----------------------------------------------------------
COUNT="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "sigdep-${SCOPE}-*.sql.gz" | wc -l | tr -d ' ')"
TOTAL="$(du -sh "$BACKUP_DIR" 2>/dev/null | cut -f1)"
echo "[$(date +%F' '%T)] Terminé — ${COUNT} dump(s) '${SCOPE}' conservé(s), ${TOTAL} au total dans ${BACKUP_DIR}."
