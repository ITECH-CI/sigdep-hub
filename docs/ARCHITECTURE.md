# Architecture

This document covers the moving parts of `sigdep-hub`: how the modules
fit together, what the data flow looks like, how authentication and
authorisation work, and how indicators are computed.

## Modules and processes

`sigdep-hub` is a Maven multi-module project that builds **three Spring
Boot processes** and a static React bundle:

```
sigdep-hub/
├── core-domain/     ← library: JPA entities, repositories, indicator services
├── ingestion-api/   ← Spring Boot app, port 8090. WRITES to PostgreSQL.
├── console-api/     ← Spring Boot app, port 8041. READS from PostgreSQL.
├── console-web/     ← React + Vite SPA, port 5173 in dev, bundled in prod.
└── infra/           ← docker-compose, nginx config.
```

`core-domain` is a plain Java library — no `main()`, no controller. Both
APIs depend on it. Splitting the writer (ingestion-api) from the reader
(console-api) lets us scale them independently and reduces the blast
radius of a bug in the read path.

Liquibase changelogs live in `ingestion-api` because it is the sole writer.
The console runs with `spring.liquibase.enabled=false`.

## Runtime topology

In both dev and prod, all browser traffic goes through a single nginx
origin. This keeps CORS trivial (SPA and API share one origin).

```
                              ┌─────────── localhost:9000 (nginx) ──────────┐
                              │                                              │
                              │   /api/* ─────────────►  console-api (:8041) │
                              │   /api/auth/*  (login, refresh, me, logout)  │
                              │   /actuator/*                                │
                              │                                              │
   browser ────────────────►  │   /api/v1/sync/* (prod) ►  ingestion-api     │
   (Chrome, Firefox, …)       │                            (:8090)           │
                              │                                              │
                              │   /  (everything else) ►  Vite dev server    │
                              │                            (dev) or built    │
                              │                            SPA assets (prod) │
                              └──────────────────────────────────────────────┘

   sigdep-sync (per site) ──── HTTPS, X-API-Key ──►  ingestion-api directly
                                                    (often bypassing nginx
                                                     in tightly controlled
                                                     private networks)
```

Auth v2.0 : l'authentification est intégrée à `console-api` (Spring
Security + JWT HS256). Plus de serveur d'identité séparé ; nginx ne
route que les apps. En prod, même topologie avec l'URL publique TLS.

## Data model (high-level)

The full reference is the Liquibase changelogs under
`ingestion-api/src/main/resources/db/changelog/v1.0/`. Three schemas:

- **`core`** — operational data (patients, sites, visits, lab results,
  treatment initiations, closures, dispensations, TPT records).
- **`analytics`** — reserved for materialised views and aggregates if /
  when we need them. Empty for now.
- **`audit`** — append-only logs. Today: `audit.sync_batch` (one row per
  call to `/api/v1/sync/*`, see the Synchronisation page).

Geographic hierarchy: `core.regions` → `core.districts` → `core.sites`.
Every patient sits in exactly one site. The cascading region/district/site
filter on the console and the `AuthScope` ceiling both follow this tree.

Records are **soft-deleted**: every business table carries a `voided`
boolean; queries filter `WHERE voided = FALSE`. The agent never sends
hard deletes either.

JSON flexibility: visits and a few other entities have a JSONB
`extra_data` column. We promote a column for any field we report on
(WHO stage, TB screening, viral load, ARV regimen, TPT status, …) but
keep the rest in `extra_data` for ad-hoc queries.

## Authentication

Auth v2.0 — Spring Security pur, intégré à `console-api` (plus de
Keycloak). Deux mécanismes :

- **Utilisateurs (console)** : login email/mot de passe sur
  `POST /api/auth/login` → access token **JWT HS256** (TTL 1h) +
  refresh token opaque (TTL 7j, stocké en `auth.refresh_tokens`,
  rotation à chaque refresh). Le SPA garde les deux en localStorage et
  envoie `Authorization: Bearer …` sur chaque appel ; un `JwtAuthFilter`
  valide la signature et reconstruit le principal (`AuthenticatedUser`)
  depuis les claims (`sub`, `uid`, `role`, `regionId`/`districtId`/`siteId`).
- **Agents (ingestion)** : clé API opaque par site dans l'en-tête
  `X-API-Key`, vérifiée par BCrypt contre `auth.api_keys`
  (`ApiKeyAuthFilter`).

Les mots de passe sont hachés en BCrypt (`auth.users`). Le secret de
signature est fourni via `SIGDEP_JWT_SECRET` (≥ 32 octets).

### Roles

| Role             | Scope     | What it can do                                     |
| ---------------- | --------- | -------------------------------------------------- |
| `SUPER_ADMIN`    | national  | Everything, including user management.             |
| `IT_ADMIN`       | national  | Technical admin (users, sync log). No CSV exports. |
| `NATIONAL_VIEWER`| national  | Read indicators and listings, country-wide.        |
| `AUDITOR`        | national  | Read-only across all sites, including audit logs.  |
| `ANALYST`        | national  | Read listings and patient detail for analytics.    |
| `REGIONAL_COORD` | region    | Same as `NATIONAL_VIEWER` but clamped to a region. |
| `DISTRICT_COORD` | district  | Clamped to a district.                             |
| `SITE_USER`      | site      | Clamped to a single site.                          |

### Geographic scoping (`AuthScope`)

A `REGIONAL_COORD` / `DISTRICT_COORD` / `SITE_USER` has a row in
`auth.user_geo_scope` carrying one of `regionId` / `districtId` /
`siteId`. `JwtService` projects that scope into the access token claims
of the same name at login time.

Every controller request goes through
`AuthScope.effective(uiRegion, uiDistrict, uiSite)` before reaching the
service layer. The method combines the JWT ceiling with the UI-supplied
scope (the cascade in the page header) using **tightest wins**:

- If the JWT carries a `siteId`, the user is locked to that site.
  Anything the UI sends in `regionId` / `districtId` is silently dropped
  — the request never sees data outside the locked site.
- Same for `districtId` (locked to that district, can refine to a site
  inside).
- Same for `regionId` (locked to the region, can refine to a district or
  site inside).
- National roles (the first 5 in the table above) get `Scope.NONE` and
  see the UI scope unchanged.

This is enforced **server-side**. A `SITE_USER` who manually adds
`?regionId=42` to the URL gets clamped back; the listing query filters
on their site regardless.

The patient detail / encounters endpoints additionally call
`PatientQueryService.isVisibleTo(patientId, …)` to 403 cross-zone reads
of a known patient id.

**Known gap:** the dashboard KPIs are still computed at national level
for everyone. Scoping the 7 SQL queries plus the cache key is on the
backlog. Listings, indicators and exports are properly scoped.

## Synchronisation flow

```
   agent.POST  /api/v1/sync/patients      ┐
   agent.POST  /api/v1/sync/visits        │ each call is a "batch":
   agent.POST  /api/v1/sync/closures      │ one batchId, ~500 to 20 000
   agent.POST  /api/v1/sync/lab_results   │ records of one entity type.
   agent.POST  /api/v1/sync/tpt_records   │
   agent.POST  /api/v1/sync/treatment_initiations
   agent.POST  /api/v1/sync/dispensations ┘
```

For every call, the `SyncController`:

1. Resolves the site by its code.
2. Opens an `audit.sync_batch` row (`status=ok` provisionally), in its
   own `REQUIRES_NEW` transaction so audit survives even if the ingest
   transaction rolls back.
3. Hands the batch off to the entity-specific writer (`PatientWriter`,
   `VisitWriter`, …) which upserts on `(site_id, source_uuid)`.
4. Closes the audit row with the final counts and a sample of error
   labels.
5. Calls `sites.touchLastSyncAt(siteId)` so the sites table reflects
   freshness.

The Synchronisation page in the console reads `audit.sync_batch` for its
KPIs, daily volume chart and recent-batches listing. Sites that haven't
synced in 24h / 7d / ever appear in the "Sites en retard" view.

## Indicator computation

Indicators are computed live (no materialised views yet). Each business
page hits its own `*Service` (BiologyService, PharmacyService,
PepfarService, …) which executes the SQL and shapes the response into
plain Java records.

PEPFAR indicators follow the MER definitions: TX_NEW / TX_CURR / TX_PVLS
with the age-band / sex disaggregation. Fiscal year is the USAID
convention (FY24 Q1 = Oct-Dec 2023). See `PepfarService` for the exact
queries and the grace period used for TX_CURR.

Caching: only the public KPIs (landing page) and the dashboard KPIs are
cached (Caffeine). Other endpoints are fast enough that the cache
overhead is not worth the scope-keying complexity.

## Frontend

React 18 + Vite + TypeScript + Tailwind + TanStack Query + Recharts. The
SPA is purely a presentation layer over the console-api: no business
logic lives in the browser.

Key conventions:

- All API calls go through `console-web/src/api/client.ts`. It owns the
  Bearer token injection and the typed response shapes.
- Authentication is wrapped by `react-oidc-context`. The `RequireAuth`
  HOC redirects unauthenticated users to the landing page.
- Tailwind palette: `sigdep` teal is primary; `accent` indigo is the
  admin section accent. Status colours follow the `StatusBadge` enum
  (`ok` / `warning` / `danger` / `info` / `neutral`).
- Icons: `lucide-react`, tree-shaken — pick the component you need from
  the import statement, no global stylesheet.

## Pieces that are intentionally *not* here

- **No write API from the console.** The console is read-only on
  business data; the only writes happen via the Users / Sites admin
  pages (accounts + API keys in the `auth` schema) and the
  ingestion-api (agents).
- **No background jobs / schedulers.** Indicators are computed on demand
  per-request. If we need scheduled materialisation, it will live behind
  a clear interface (Spring `@Scheduled` in a dedicated module).
- **No event bus.** Sync is synchronous request/response; the agent
  retries on network failures using its local SQLite buffer.
