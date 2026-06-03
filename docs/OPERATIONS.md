# Operations

Day-to-day recipes for working on or with `sigdep-hub`. The
[ARCHITECTURE.md](ARCHITECTURE.md) doc explains the why; this one focuses
on commands you actually type.

## Starting and stopping the stack

```bash
# Start: postgres + nginx
cd infra && docker compose up -d

# Stop everything (state preserved)
cd infra && docker compose down

# Nuke state — la base (données + comptes) est réinitialisée au prochain `up`
cd infra && docker compose down -v
```

Once infra is up, the three Spring Boot / Vite processes run on the host
(not in containers, to keep iteration fast):

```bash
(cd ingestion-api && ./run.sh --dev)              # 8090, runs Liquibase
(cd console-api    && ./run.sh --dev)             # 8041
(cd console-web    && npm run dev)                # 5173 (via nginx :9000)
```

`run.sh --dev` uses Maven `spring-boot:run` (picks up local code changes
and reinstalls `core-domain` first). `run.sh` without `--dev` runs the
packaged JAR — closer to prod.

## Comptes & authentification (auth v2.0)

L'auth est gérée par console-api (Spring Security + JWT). Tout passe par
la base `auth.*` ; aucun outil externe (plus de `kcadm.sh`).

### Premier compte (seed)

Au premier boot, si `auth.users` est vide, console-api crée le
SUPER_ADMIN à partir de `SIGDEP_ADMIN_EMAIL` / `SIGDEP_ADMIN_PASSWORD`.
La suite se gère depuis la page **Utilisateurs** (`/app/users`).

### Inspecter les comptes en base

```bash
docker exec sigdep-postgres psql -U sigdep -d sigdep -c \
  "SELECT id, email, role, user_level, active FROM auth.users ORDER BY id;"

# Portée géo d'un utilisateur
docker exec sigdep-postgres psql -U sigdep -d sigdep -c \
  "SELECT * FROM auth.user_geo_scope WHERE user_id = 1;"
```

### Débloquer / réinitialiser

La création, le changement de rôle/portée et le reset de mot de passe se
font depuis la page Utilisateurs. En dépannage, on peut forcer un
mot de passe via l'API (`POST /api/v1/users/{id}/password`) avec un JWT
SUPER_ADMIN, ou réactiver un compte (`active = true`).

### Clés API des agents

Génération / révocation depuis la page **Sites** (bouton « Gérer »).
État en base :

```bash
docker exec sigdep-postgres psql -U sigdep -d sigdep -c \
  "SELECT site_id, key_prefix, created_at, last_used_at, revoked_at FROM auth.api_keys;"
```

Le UUID en clair n'est jamais stocké — s'il est perdu, régénérer une clé
(l'ancienne est révoquée automatiquement).

## Liquibase / database

Liquibase is part of `ingestion-api`. It runs on every startup.

```bash
# Inspect the changelog history
docker exec sigdep-postgres psql -U sigdep -d sigdep \
  -c "SELECT id, author, dateexecuted FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 10;"

# Connect to the DB
docker exec -it sigdep-postgres psql -U sigdep -d sigdep
```

To add a migration: drop a new XML file in
`ingestion-api/src/main/resources/db/changelog/v1.0/`, register it in
`db.changelog-master.xml`, and restart `ingestion-api`. **Never edit a
migration that has already been applied** — write a new one.

## Sync audit (`audit.sync_batch`)

Every call to `/api/v1/sync/*` writes a row. The Synchronisation page in
the console reads it; for quick CLI diagnostics:

```sql
-- Recent batches
SELECT id, site_code, entity_type, received_count, accepted, rejected,
       status, duration_ms, finished_at
FROM audit.sync_batch
ORDER BY finished_at DESC
LIMIT 20;

-- Sites that haven't synced in 7 days
SELECT s.code, s.name, s.last_sync_at
FROM core.sites s
WHERE s.last_sync_at IS NULL OR s.last_sync_at < NOW() - INTERVAL '7 days'
ORDER BY s.last_sync_at NULLS FIRST;
```

## Troubleshooting

Issues we hit while building, with the diagnosis baked in.

### `bad SQL grammar` / `operator does not exist: bigint = character varying`

A query uses both `?`-bound geo args and other `?`-bound params, and the
ordering in the SQL string drifted from the ordering in the args array.
The fix is usually to **inline the constants** (concept UUIDs, etc.) and
keep only the dynamic args as `?`. See the fix in `ClinicService` from
mid-May 2026 for the canonical pattern.

### `403 insufficient_scope` on a listing endpoint

The user has no role in the endpoint's `@PreAuthorize` whitelist. Check
the controller — `SITE_USER` and `DISTRICT_COORD` must be listed alongside
the national roles for the geographic scoping to work. If they aren't,
the request is rejected *before* `AuthScope` even runs.

### Site-scoped user still sees everything

In order:

1. La portée géo de l'utilisateur est-elle correcte ? Vérifier
   `auth.user_geo_scope` (voir la section Comptes) et le rôle dans
   `auth.users`.
2. L'utilisateur s'est-il reconnecté depuis le changement de portée ?
   Les claims de portée sont posés dans le JWT au login ; il faut un
   nouveau login (ou un refresh) pour les rafraîchir.
3. Le rôle est-il bien zone-bound ? Seuls `REGIONAL_COORD` /
   `DISTRICT_COORD` / `SITE_USER` sont restreints ; les rôles nationaux
   voient tout par conception.

### CORS errors on Firefox

If you're hitting `/api/*` and Firefox refuses with "CORS désactivé", you
are probably on a port that isn't behind the nginx proxy. Always use
`http://localhost:9000` in dev — that's the whole point of the nginx
single-origin setup. Direct hits to `:5173` or `:8041` work in
Chrome but not in Firefox with strict tracking protection.

### 401 sur toutes les requêtes après login

Le `SIGDEP_JWT_SECRET` a probablement changé entre l'émission du token et
sa vérification (chaque redémarrage avec un secret différent invalide les
tokens). Fixer un secret stable et se reconnecter.

### `Liquibase: ChangeSet ... has already been ran with checksum`

You modified an applied migration. **Don't** edit it back — write a new
migration that produces the desired state. If you really need to fix
the original (only in dev, never in prod), update the checksum in
`databasechangelog` or `docker compose down -v` and let it re-run from
zero.

### Vite dev server returns 502 from nginx

nginx can't reach Vite. Two common causes:

- Vite is bound to 127.0.0.1 only. Make sure `vite.config.ts` has
  `server.host: true`.
- The Vite process is down — check the `npm run dev` terminal.

If you get `Blocked request. This host ("vite") is not allowed.`, the
upstream name leaked into the Host header. Solutions: keep `vite` in
`allowedHosts`, or make sure every `proxy_set_header Host $host;` is
restated inside each nginx `location` block (nginx resets inheritance
when you add any `proxy_set_header` in a block).

## Cleaning a stale dev environment

If things get weird (db schema mismatch, mystery 401s):

```bash
cd infra
docker compose down -v
docker compose up -d
# Relancer ingestion-api (applique Liquibase) puis console-api
# (re-seede le SUPER_ADMIN si SIGDEP_ADMIN_* sont définis).
```

La base est repartie de zéro : les migrations Liquibase recréent les
schémas (`core` / `audit` / `auth`) au démarrage d'ingestion-api.
