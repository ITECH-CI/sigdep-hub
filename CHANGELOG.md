# Changelog — sigdep-hub

Le format suit [Keep a Changelog](https://keepachangelog.com/) et la
plateforme adhère à [Semantic Versioning](https://semver.org/).

> Note : les entrées 2.1.0 → 2.1.2 n'ont pas été reportées dans ce
> fichier au fil de l'eau ; voir les tags Git et l'historique des commits
> pour le détail. La 2.1.3 reprend le suivi ci-dessous.

## [2.1.9] — 2026-08-10

### Corrigé

- **Initiations à `arv_init_date` NULL invisibles dans les vues « par période »**
  (problème préexistant, révélé par le nouveau filtre de dates). Sur le hub de
  prod, ~64 % des initiations ont une date ARV vide : elles étaient comptées dans
  le cumul « toutes périodes » mais **exclues** dès qu'un filtre de période
  s'appliquait (`arv_init_date >= ?` élimine les NULL). Ces lignes ont toutes une
  `enrollment_date` renseignée : les requêtes d'initiation bornent désormais sur
  `COALESCE(arv_init_date, enrollment_date)` (KPI période, graphe annuel, liste,
  distributions). Sur prod : le compte « période » sur une plage large passe de
  ~7 000 à ~19 570 (≈ cumul).

### Modifié

- **Filtre de période : bouton « Appliquer »**. Les champs date début/fin
  modifient désormais un état local et ne déclenchent la requête qu'au clic sur
  « Appliquer » (ou touche Entrée), au lieu d'une requête à **chaque caractère
  saisi** — inutilement coûteux sur les gros volumes. Les raccourcis « N derniers
  mois », eux, s'appliquent immédiatement.

## [2.1.8] — 2026-08-09

### Ajouté

- **Filtre de période « date début → date fin »** sur les pages console
  (Biologie, Dépistage, Pharmacie, TPT, Clinique, PTME). Les raccourcis
  « N derniers mois » sont conservés (ils préremplissent les dates) mais deux
  champs date début/fin sont désormais toujours visibles et éditables. Côté API,
  les endpoints métier acceptent `?from=YYYY-MM-DD&to=YYYY-MM-DD` (bornes
  incluses) ; absent → 12 mois glissants (rétrocompatible). Composant réutilisable
  `PeriodFilter` (front) et `PeriodRange` (domaine, avec `resolve()` testé).
- **Script de sauvegarde Postgres** `infra/scripts/backup-hub.sh` : dump
  compressé horodaté + rotation, périmètre `--scope sigdep` (base métier) ou
  `--scope full` (`pg_dumpall`, dashboards Superset + rôles inclus). Le hub
  n'avait aucun backup automatisé auparavant. À planifier en cron (voir
  `infra/scripts/README.md` et `docs/DEPLOYMENT.md`).

### Modifié

- Titre de la page d'accueil : « Base de Données Centrale Consolidée des patients
  vivant avec le VIH en Côte d'Ivoire ».

## [2.1.7] — 2026-08-07

### Corrigé

- **nginx : coupures de transport pendant le backfill sync** (cause racine de
  SYNC-12 côté serveur). `keepalive_requests` relevé de 1000 (défaut) à 100000 :
  lors d'un backfill, l'agent enchaîne des milliers de POST sur une même
  connexion HTTP/2 et, au 1000ᵉ, nginx envoyait un `GOAWAY` — vu par OkHttp
  comme `stream was reset: CANCEL` sur `LAB_RESULTS`. Depuis nginx 1.19.7, cette
  directive couvre aussi HTTP/2. `proxy_read_timeout`/`proxy_send_timeout` portés
  à 300 s sur `/api/v1/sync/` (un lot lourd peut mettre plusieurs dizaines de
  secondes à être persisté). Config de reverse-proxy : à appliquer par
  `nginx -s reload`, sans rebuild d'image.

## [2.1.6] — non publié

### Corrigé

- **Backfill de visites historiques rejeté (migration 043)** : `core.visits`
  est partitionnée par `visit_date` ; les partitions ne couvraient que
  2010→2030. Un site avec des visites dès 2001 était rejeté en masse
  (`UPSERT_FAILED` — « no partition of relation "visits" found for row »).
  Ajout des partitions annuelles **2000→2009** (range 2000→2030 continu).
  Idempotent (`CREATE TABLE IF NOT EXISTS`).

## [2.1.5] — non publié

### Corrigé

- **Colonnes VARCHAR non bornées (migration 042)** : rejets `value too long`
  lors de l'ingestion d'un 2e site, sur des colonnes alimentées par
  `ObsPivot.asString` (nom de concept OpenMRS codé ou texte libre — longueur
  arbitraire). Élargissements :
  `treatment_initiations.hiv_type` (20→100),
  `patients.marital_status` / `education_level` / `religion` (50→100),
  `tpt_records.tpt_order_number` / `tpt_regimen`, `visits.tpt_regimen`,
  `treatment_initiations_pediatric.screening_code`, `lab_results.unit`
  (50→100), et `screenings.gender` (2→10, durcissement). Migration
  idempotente (`modifyDataType`).

## [2.1.4] — non publié

### Ajouté

- **Indicateurs PEPFAR — désagrégation d'âge MER fine** : passage de 4
  tranches grossières (`<15/15-24/25-49/50+`) aux 12 tranches MER standard
  (`<1, 1-4, 5-9, 10-14, 15-19, 20-24, 25-29, 30-34, 35-39, 40-44, 45-49,
  50+`). Tableaux réagencés : tranches d'âge en **colonnes**, sexe en
  **lignes** (disposition MER classique). Couvre TX_NEW, TX_CURR, TX_PVLS,
  HTS, PMTCT, TB_PREV. (`764967a`)

### Corrigé

- **Superset — état de l'éditeur SQL Lab** : ajout de caches partagés
  (`FileSystemCache` dans `superset_home`) pour `CACHE_CONFIG`,
  `DATA_CACHE_CONFIG`, `FILTER_STATE_CACHE_CONFIG` et
  `EXPLORE_FORM_DATA_CACHE`. Sans backend de cache partagé entre workers,
  la persistance d'état de SQL Lab échouait. (`c3eacc7`)

## [2.1.3] — non publié

### Corrigé

- **Résolution en masse des rejets (bulk-resolve)** : la comparaison de
  `entity_type` est désormais insensible à la casse. L'ingestion écrit le
  type en minuscules (`treatment_initiations`…) alors que `bulkResolveLanded`
  le passait en majuscules avant le `WHERE`, d'où un `{"resolved":0}` malgré
  des rejets bien présents. Corrige notamment le blocage des ~488 k rejets
  `treatment_initiations`. (`661a2ca`)

### Déploiement

- **`COMPOSE_PROJECT_NAME`** ajouté au `.env.example` : fige le préfixe des
  volumes Docker (`postgres_data`, `superset_home`) sur le nom de projet
  plutôt que sur le nom du dossier de déploiement — renommer/déplacer le
  dossier n'orpheline plus les volumes. (`bb9227a`)
- Domaine d'exemple aligné sur `sigdephub` (sans suffixe `-v3`). (`2d06f53`)

## [2.0.0] — non publié

### Changé — migration de l'authentification : Keycloak → Spring Security + JWT

Keycloak accumulait trop de friction pour un cas d'usage à une seule
application (redirect URIs à patcher, realm à importer, base/user
dédiés, bouton « Se connecter » capricieux). L'auth est désormais
intégrée au hub, sans serveur d'identité séparé.

- **Auth console** : Spring Security pur + JWT **HS256** (clé symétrique
  en `.env`). Login par email/mot de passe (`POST /api/auth/login`),
  access token 1h + refresh token opaque 7j (rotation à chaque refresh,
  stocké en base `auth.refresh_tokens`). Endpoints `/api/auth/*`
  (`login`, `me`, `refresh`, `logout`).
- **Auth agent** : clé API opaque par site (en-tête `X-API-Key`,
  hash BCrypt en base `auth.api_keys`) en remplacement du bearer OAuth.
  Génération/révocation depuis la console (page **Sites**).
- **Modèle de comptes** : un compte = un email + un rôle unique parmi
  les 8 rôles + un niveau (`NATIONAL`/`REGION`/`DISTRICT`/`SITE`) + au
  plus une portée géo. Table `auth.users` (BCrypt) + `auth.user_geo_scope`.
- **Seed initial** : compte SUPER_ADMIN créé au premier boot via
  `SIGDEP_ADMIN_EMAIL` / `SIGDEP_ADMIN_PASSWORD` si `auth.users` est vide.
- **Schéma DB** : nouveau schéma `auth` (changesets Liquibase 035-038).
- **SPA** : `react-oidc-context` retiré ; nouvelle page de login, contexte
  d'auth JWT (localStorage), intercepteur 401 → refresh → retry.

### Retiré

- Service `keycloak` des `docker-compose` (dev + prod), `infra/keycloak/`,
  `infra/postgres/01-init-keycloak.sh`, routes nginx `/realms`, `/admin`…
- `KeycloakAdminService` / `KeycloakAdminConfig`, dépendances
  `keycloak-admin-client` et `spring-boot-starter-oauth2-resource-server`.
- Variables d'environnement `KEYCLOAK_*` / `KC_*` (hub et agent).

### Migration

Aucune migration de données : on repart d'une base vierge pour le pilote
(pas d'utilisateurs Keycloak à reprendre). Fournir `SIGDEP_JWT_SECRET`
(≥ 32 octets) et `SIGDEP_ADMIN_EMAIL` / `SIGDEP_ADMIN_PASSWORD` au premier
démarrage ; régénérer les clés API des sites depuis la console.

## [1.0.4] — 2026-05-24

### Corrigé — déploiement pilote v1.0.3 inutilisable hors environnement de build

- **Redirect URIs Keycloak portables** : le client SPA `sigdep-console`
  avait `http://localhost:9000/*` codé en dur dans `realm-sigdep.json`,
  rendant impossible la connexion depuis n'importe quelle URL prod.
  Le realm utilise maintenant un placeholder `__PUBLIC_ORIGIN__`
  substitué au démarrage par un entrypoint Keycloak custom
  (`infra/keycloak/entrypoint.sh`) à partir de la variable d'env
  `PUBLIC_ORIGIN`.
- **Base Keycloak auto-créée** : `infra/postgres/01-init-keycloak.sh`
  monté dans `/docker-entrypoint-initdb.d/` crée `CREATE USER keycloak`
  + `CREATE DATABASE keycloak` au premier boot de Postgres. Fini les
  commandes `psql` manuelles avant le 1er `docker compose up`.
- **Healthchecks Docker pointaient sur le mauvais port** :
  `ingestion-api` (8090) et `console-api` (8041) avaient un
  `HEALTHCHECK` codé sur 8080, ce qui laissait les conteneurs en
  `health: starting` indéfiniment. Corrigé dans les deux Dockerfiles.
- **502 nginx vers Keycloak** : `proxy_buffer_size` par défaut trop
  petit pour les headers volumineux de Keycloak (cookies de session
  + JWT). Buffers augmentés à 16k/8×16k/32k dans `nginx.prod.conf`.

### Documentation

- `installer-hub.md` : section dédiée à la reconstruction d'un
  `fullchain.pem` propre pour un cert wildcard CA commerciale
  (saut de ligne obligatoire entre certs), avec commande de
  vérification `openssl crl2pkcs7`.
- Note sur les caractères spéciaux dans les mots de passe (`!`, `$`,
  etc.) et leur échappement dans `.env`.

### Tarball de déploiement

- Le tarball `sigdep-hub-deploy-1.0.4.tar.gz` embarque maintenant le
  dossier `postgres/` (script init) en plus de `keycloak/`,
  `nginx/`, `docker-compose.yml`.

## [1.0.3] — 2026-05-21

### Ajouté

- **Conteneur console-web câblé dans la stack prod** : le SPA est
  désormais servi par l'image GHCR `sigdep-console-web` derrière le
  nginx front (plus de bundle à monter depuis le hôte).
- **Bundle de déploiement** : un tarball
  `sigdep-hub-deploy-<version>.tar.gz` est attaché à chaque release
  GitHub, contenant docker-compose, nginx, realm Keycloak, thème et
  `.env.example`. Plus de `git clone` côté serveur.

### Documentation

- Guide [installer-hub.md](docs/user-guide/deploiement/installer-hub.md)
  réécrit autour du bundle de release.
- README racine et 3 READMEs voisins (sync, contracts) traduits en
  français.
- Owners GHCR figés sur `ghcr.io/itech-ci/sigdep-*` dans toute la doc.

## [1.0.0] — 2026-05-21

Première release fonctionnelle de SIGDEP-3. Plateforme complète pour
le suivi des patients VIH en Côte d'Ivoire, avec ingestion depuis
des sites OpenMRS, agrégation centrale et console web pour le PNLS.

### Modules métier

- **Patients** : registre central avec identité, socio-démographie
  (profession, niveau d'étude, religion, situation matrimoniale),
  identifiants nationaux (UPID, CODE ARV, CMU).
- **Suivi clinique** éclatée en quatre onglets correspondant au
  parcours patient : Initiations ARV → Visites → IVSA → Clôtures.
- **Pharmacie / ARV** : dispensations dérivées des visites
  (`arv_treatment_days`) avec distribution par régime.
- **Dépistage** (HIV screening) anonyme, avec section dédiée
  Porte d'entrée (volume + positivité par point d'accès).
- **PTME** Mère + Enfant : suivi des femmes enceintes VIH+ et des
  enfants exposés (PCR1/2/3, sérologie).
- **TPT** (Traitement Préventif Tuberculose) : initiation, suivi,
  résultats.
- **Biologie** : CD4, charge virale, agrégés par patient.

### Indicateurs PEPFAR

Cascade complète, désagrégée par tranche d'âge × sexe :

- **TX_NEW**, **TX_CURR**, **TX_PVLS** (cascade traitement)
- **HTS_TST**, **HTS_POS** + positivité (dépistage)
- **PMTCT_STAT**, **PMTCT_ART**, **PMTCT_EID** (prévention M-E)
- **TB_PREV** (tuberculose préventive)
- **File active par modèle de soin** (donut Standard / IVSA / Échec)

Année fiscale USAID (oct→sep), sélecteur trimestriel.

### Console web

- **Vue d'ensemble** : 4 KPIs principaux + file active 12 mois +
  alertes de synchronisation + répartition par région.
- **8 pages thématiques** (PEPFAR, Patients, Sites, Suivi clinique,
  Pharmacie, Dépistage, PTME, TPT, Biologie) avec sélecteur géo
  (Région → District → Site) et export CSV partout.
- **Page patient** avec chronologie complète (visites, init,
  clôture, lab).
- **Visuels empruntés au pbix existant** : visites vs dispensations,
  attendus vs venus, répartition régionale, donut MSD.

### Administration

- **Page Synchronisation** : batches reçus par site, distribution
  quotidienne, sites en retard.
- **Onglet Rejets** persistant avec workflow OPEN → DEAD_LETTER →
  marquer-comme-résolu.
- **Page Utilisateurs** : créer / modifier / désactiver / reset
  password via le client `sigdep-console-admin`.
- **Rôles + scope** : 8 rôles Keycloak (`SUPER_ADMIN`, `IT_ADMIN`,
  `NATIONAL_VIEWER`, `REGIONAL_COORD`, `DISTRICT_COORD`, `SITE_USER`,
  `ANALYST`, `AUDITOR`) avec ceiling JWT + tightest-wins narrowing.

### Sécurité & déploiement

- Single-origin nginx reverse-proxy sur `:9000` (dev) / TLS (prod).
- Keycloak 25 avec thème SIGDEP personnalisé (login FR, palette
  sigdep-*, logo).
- Liquibase pour le schéma SQL (33 changesets).
- Cache Caffeine sur les KPIs lourds (TTL 60s, clé scope-aware).
- docker-compose pour dev et prod.

### Documentation

- `docs/ARCHITECTURE.md`, `docs/OPERATIONS.md`, `docs/DEPLOYMENT.md`,
  `CONTRIBUTING.md` — pour développeurs et opérateurs.
- `docs/user-guide/` — 11 fichiers markdown couvrant coordinateur
  national/régional/district, site user, administrateur et déployeur.

### Scripts opérationnels

- `infra/scripts/reset-hub.sh` : TRUNCATE des tables métier sans
  toucher au realm Keycloak ni aux référentiels.
- `infra/scripts/import_realm.sh` : importer / réimporter le realm.

### Connu mais non bloquant pour v1

- `core.dispensations` reste vide par design : dans SIGDEP la
  dispensation est un champ sur la visite, pas un encounter séparé.
  La métrique « Dispensations » est calculée depuis
  `core.visits.arv_treatment_days`.
- `PMTCT_STAT` et `PMTCT_ART` utilisent des heuristiques sur les
  labels (`ILIKE '%ARV%'`, etc.) à affiner sur données réelles.
- `TX_RTT` et `TX_ML` hors scope v1 (nécessitent un modèle
  d'interruption en traitement).
- Pas de tests automatisés. Dette technique reconnue.
- Pas de carte SVG des régions CI : bar chart horizontal en
  stand-in (même donnée, prêt pour un upgrade ultérieur).

[1.0.0]: https://github.com/ITECH-CI/sigdep-hub/releases/tag/v1.0.0
