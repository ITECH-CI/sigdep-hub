# Deployment

`infra/docker-compose.prod.yml` is the production-shaped compose file.
It reproduces the dev topology (single nginx origin, separate
console-api / ingestion-api processes, shared PostgreSQL) but with TLS,
secrets and image tags pulled from the environment. Auth is handled by
console-api itself (Spring Security + JWT) — no separate identity server.

The file is a **working skeleton**, not a turnkey deployment. Every
placeholder labelled `change-me` must be replaced before a production
install. This document is the checklist.

## Prerequisites

- A host with Docker 24+ and Compose v2.
- A DNS name pointing at the host (e.g. `sigdep.example.org`).
- TLS certificates for that name. Let's Encrypt via certbot is fine; for
  staging you can use mkcert.
- A separately backed-up Postgres instance, or trust the local volume
  with backups (see below).
- Network rules: only port 443 needs to be reachable from the outside;
  agents POST to the same hostname over HTTPS.

## One-time setup

### 1. Provide TLS certs

Drop your full chain and private key into `infra/nginx/certs/` next to
`docker-compose.prod.yml`:

```
infra/nginx/certs/
├── fullchain.pem
└── privkey.pem
```

`nginx.prod.conf` mounts this directory read-only into the container.

### 2. Pull the published images

Three images are published to GHCR on every `v*.*.*` tag by the
[`release.yml`](../.github/workflows/release.yml) workflow:

- `ghcr.io/<owner>/sigdep-ingestion-api:<version>`
- `ghcr.io/<owner>/sigdep-console-api:<version>`
- `ghcr.io/<owner>/sigdep-console-web:<version>` (serves the SPA on
  port 80, fronted by the prod nginx)

The compose file resolves them via `INGESTION_API_IMAGE`,
`CONSOLE_API_IMAGE`, `CONSOLE_WEB_IMAGE` env vars (see §3). No host-side
SPA build is needed any more — the `console-web` image embeds the
bundle.

### 3. Set secrets

`docker-compose.prod.yml` reads sensitive values from environment
variables. Put them in `/etc/sigdep/sigdep-hub.env` (mode 0600) or use
your secret manager. At minimum:

```bash
POSTGRES_PASSWORD=...
SIGDEP_JWT_SECRET=...            # >= 32 octets, openssl rand -base64 48
SIGDEP_ADMIN_EMAIL=admin@sigdep.example.org
SIGDEP_ADMIN_PASSWORD=...        # seed du SUPER_ADMIN au 1er boot
PUBLIC_ORIGIN=https://sigdep.example.org
CONSOLE_API_IMAGE=ghcr.io/<owner>/sigdep-console-api:<version>
INGESTION_API_IMAGE=ghcr.io/<owner>/sigdep-ingestion-api:<version>
CONSOLE_WEB_IMAGE=ghcr.io/<owner>/sigdep-console-web:<version>
```

Then run compose with the file:

```bash
docker compose --env-file /etc/sigdep/sigdep-hub.env \
  -f docker-compose.prod.yml up -d
```

### 4. First boot — SUPER_ADMIN

Au premier démarrage, console-api crée le compte SUPER_ADMIN à partir de
`SIGDEP_ADMIN_EMAIL` / `SIGDEP_ADMIN_PASSWORD` si la table `auth.users`
est vide. Vérifier dans les logs :

```bash
docker compose logs console-api | grep -i SUPER_ADMIN
```

Se connecter sur `https://sigdep.example.org/`, puis créer les autres
comptes via la page Utilisateurs. Retirer ensuite `SIGDEP_ADMIN_PASSWORD`
de l'environnement.

### 5. Wire the agents

Each `sigdep-sync` site agent needs:

- `SIGDEP_CENTRAL_API_URL=https://sigdep.example.org`
- `SIGDEP_API_KEY=<clé générée dans la console, page Sites>`
- `SIGDEP_SITE_CODE=<the local site code>`

See `sigdep-sync/README.md` for the install procedure (systemd unit on
Linux sites, WinSW / NSSM wrapper on Windows sites).

## Operational concerns

### Backups

Utiliser le script fourni **`infra/scripts/backup-hub.sh`** (dump compressé
horodaté + rotation + vérification d'intégrité). Deux périmètres :

- `--scope sigdep` (défaut) : base `sigdep` seule — `core.*`, `audit.*` et
  `auth.*` (comptes, clés API, refresh tokens). Pas de base auth séparée à
  sauvegarder. **N'inclut PAS** les dashboards Superset.
- `--scope full` : `pg_dumpall` — toute l'instance, dont `superset_meta`
  (dashboards / datasets Superset) et les rôles Postgres. **Recommandé** pour la
  sauvegarde automatique : rien n'est perdu lors d'un déplacement de serveur.

```bash
# Cron : dump complet quotidien à 02h30, rétention 30 jours (défaut)
30 2 * * *  /opt/sigdep-hub/infra/scripts/backup-hub.sh --scope full \
            >> /var/log/sigdep-backup.log 2>&1
```

> Un dump local ne protège pas d'une perte du serveur : répliquer
> `/var/backups/sigdep` hors-site (rsync/rclone/S3) et **chiffrer** — le dump
> contient des données patients.

Détails et options : `infra/scripts/README.md`.

### Upgrades

1. Build the new images, tag them.
2. Update `CONSOLE_API_IMAGE` / `INGESTION_API_IMAGE` in the env file.
3. `docker compose pull && docker compose up -d`.
4. `ingestion-api` will run any new Liquibase migrations on startup.

Liquibase migrations should be **forward-compatible**: an older
ingestion-api should keep working against a database that has had a new
schema migration applied. In practice, this means: only add columns
(don't drop), never rename, allow null first then backfill.

### Monitoring

- Both APIs expose `/actuator/health`, `/actuator/info`,
  `/actuator/prometheus`. Nginx routes `/actuator/*` to console-api;
  ingestion-api's actuator stays on its internal port — scrape it from
  the Prometheus container directly.
- ingestion-api n'expose que `/api/v1/sync/**` (auth par clé API) ;
  son actuator reste sur le port interne — ne pas l'exposer au public.

### TLS rotation

Replace the certs in `infra/nginx/certs/` and run
`docker exec sigdep-nginx nginx -s reload`. No downtime.

### Disaster recovery

Restauration à froid depuis un dump produit par `backup-hub.sh`. La procédure
dépend du **scope** du dump (voir le nom du fichier `sigdep-<scope>-…`).

**Scope `full`** (dump `pg_dumpall`, restauration complète de l'instance —
recommandée pour un déplacement de serveur, restaure aussi Superset) :

```bash
docker compose -f docker-compose.prod.yml up -d postgres   # instance vierge
# pg_dumpall se restaure sur la base d'amorçage 'postgres' ; il recrée les
# bases (sigdep, superset_meta) et les rôles.
gunzip -c /var/backups/sigdep/sigdep-full-YYYY-MM-DD_HHMMSS.sql.gz | \
  docker exec -i sigdep-postgres psql -U sigdep -d postgres
docker compose -f docker-compose.prod.yml up -d
```

**Scope `sigdep`** (base métier seule ; le dump est `--clean --if-exists`, donc
ré-applicable sur une base existante) :

```bash
docker compose -f docker-compose.prod.yml up -d postgres
gunzip -c /var/backups/sigdep/sigdep-sigdep-YYYY-MM-DD_HHMMSS.sql.gz | \
  docker exec -i sigdep-postgres psql -U sigdep -d sigdep
docker compose -f docker-compose.prod.yml up -d
```

> Un dump `sigdep` ne restaure pas les dashboards Superset (base
> `superset_meta`) : les recréer, ou repartir d'un dump `full`.

Les agents rejouent les batches en attente depuis leur buffer SQLite local :
jusqu'à quelques heures de synchro en vol sont récupérées automatiquement.
