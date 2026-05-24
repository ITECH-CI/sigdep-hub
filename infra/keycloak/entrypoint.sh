#!/bin/bash
# Wrapper d'entrypoint pour Keycloak — substitue les placeholders du
# realm avant l'import.
#
# Le fichier realm-sigdep.json livré dans le bundle est monté en
# read-only sur /opt/keycloak/data/import-template/. Ce script le copie
# dans /opt/keycloak/data/import/ (writable, dans le conteneur) en
# substituant __PUBLIC_ORIGIN__ par la vraie valeur courante. Keycloak
# importe ensuite ce fichier patché via --import-realm.

set -euo pipefail

TEMPLATE="/opt/keycloak/data/import-template/realm-sigdep.json"
TARGET_DIR="/opt/keycloak/data/import"
TARGET="$TARGET_DIR/realm-sigdep.json"

if [[ ! -f "$TEMPLATE" ]]; then
  echo "[entrypoint] ERROR: $TEMPLATE missing — was it mounted ?" >&2
  exit 1
fi

if [[ -z "${PUBLIC_ORIGIN:-}" ]]; then
  echo "[entrypoint] WARNING: PUBLIC_ORIGIN not set ; using template as-is (only localhost redirect URIs will work)." >&2
fi

mkdir -p "$TARGET_DIR"
sed "s|__PUBLIC_ORIGIN__|${PUBLIC_ORIGIN:-http://localhost:9000}|g" "$TEMPLATE" > "$TARGET"
echo "[entrypoint] realm-sigdep.json substituted (PUBLIC_ORIGIN=${PUBLIC_ORIGIN:-fallback})"

exec /opt/keycloak/bin/kc.sh "$@"
