# Changelog — sigdep-hub

Le format suit [Keep a Changelog](https://keepachangelog.com/) et la
plateforme adhère à [Semantic Versioning](https://semver.org/).

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
