# Pilote SIGDEP-3 — checklist de déploiement

Document de suivi pour passer de « v1 prête » à « pilote en production
sur N sites ». À cocher au fur et à mesure ; chaque section indique le
**responsable** par défaut et le **livrable** attendu.

> **Périmètre du pilote v1** : 1 hub de test + 1 à 2 sites. Une fois la
> boucle complète validée (extraction → ingestion → console), on ouvre
> aux sites suivants par vagues.

## 0. Pré-pilote — décisions à figer

| # | Décision | Responsable | Statut |
|---|----------|-------------|--------|
| 0.1 | URL publique du hub (ex. `sigdep-test.sante.gouv.ci`) | PNLS + DSI | ☐ |
| 0.2 | Hébergeur du hub (serveur ministère / cloud) | PNLS + DSI | ☐ |
| 0.3 | Liste des sites pilotes (1-2) avec contact IT + référent clinique | PNLS | ☐ |
| 0.4 | ~~Registre GHCR officiel~~ — figé sur `ITECH-CI`. Images publiées sur `ghcr.io/itech-ci/sigdep-*` | I-TECH CI | ✅ |
| 0.5 | Visibilité des packages GHCR : privée (token requis) ou publique | I-TECH CI | ☐ |
| 0.6 | Politique de version : tag par site ou `:latest` partagé | I-TECH CI | ☐ |

> 0.4 — Si on bascule sur `ITECH-CI`, définir la variable de repo
> `IMAGE_REGISTRY=ghcr.io/itech-ci` dans **les deux** repos (`sigdep-sync`
> et `sigdep-hub`) avant le prochain tag.

## 1. Hub — provisionnement serveur

Responsable : **DSI / opérateur d'hébergement**.

- ☐ Serveur Linux Ubuntu 22.04+ (4 vCPU, 8 Go RAM, 100 Go disque) provisionné.
- ☐ Docker + docker compose v2 installés.
- ☐ Nom DNS pointé sur l'IP publique (`sigdep-test.sante.gouv.ci`).
- ☐ Certificat TLS obtenu (Let's Encrypt ou autorité officielle).
- ☐ Ports ouverts : 443 (entrant public), 22 (SSH restreint).
- ☐ Sauvegardes Postgres planifiées (cron + emplacement off-site).

**Livrable** : un `ssh` fonctionnel + un `curl -I https://<host>/` qui
retourne 200/301 sur le serveur cible.

## 2. Hub — installation logicielle

Responsable : **équipe SIGDEP**. Suit
[Installer le hub](installer-hub.md).

- ☐ Repo `sigdep-hub` cloné sur le serveur.
- ☐ `infra/.env` créé, mots de passe forts générés et notés dans le
  coffre-fort (Postgres, Keycloak admin, secrets clients OIDC).
- ☐ Realm Keycloak `sigdep` importé.
- ☐ Migrations Liquibase appliquées (33 changesets, `core.*` + `dim.*` +
  `mart.*`).
- ☐ Seed des `core.regions` / `core.districts` / `core.sites` chargé
  (les sites pilotes y figurent avec leur `code` définitif).
- ☐ Client Keycloak `sigdep-agent` (client_credentials) créé pour
  chaque site, secret noté.
- ☐ Utilisateur SUPER_ADMIN initial créé dans Keycloak.
- ☐ Console accessible sur `https://<host>/` en TLS, login SUPER_ADMIN OK.

**Livrable** : connexion réussie à la console + page Dashboard qui
charge (vide, mais sans 500).

## 3. Hub — smoke tests post-install

- ☐ `GET /actuator/health` → `{"status":"UP"}` sur ingestion-api ET
  console-api.
- ☐ Une insertion manuelle dans `core.sync_state` (depuis psql) est
  visible sur la page **Synchronisation** de la console.
- ☐ Un POST vide vers `/ingest/patients` avec un token agent valide
  retourne 200 (et 401 sans token).
- ☐ Les logs ingestion-api ne contiennent ni `ERROR` ni `WARN` non
  attendu après 1h en idle.

## 4. Site pilote — pré-requis

Pour **chaque** site pilote, valider avec l'IT du site avant déploiement :

- ☐ Version d'OpenMRS connue (≥ 2.x), schéma `openmrs` accessible en
  MySQL.
- ☐ Modules `htmlformentry`, `hivscreening`, `ptme` installés et utilisés
  (les formulaires PEC Initial / Suivi / Clôture sont remplis en
  routine).
- ☐ Un utilisateur MySQL **lecture seule** `sigdep_reader` créé sur
  l'instance OpenMRS (voir `installer-agent.md` § « Créer l'utilisateur
  MySQL »).
- ☐ Réseau : le poste qui hébergera l'agent peut atteindre
  `https://<hub>/` (vérifier proxy / firewall).
- ☐ Code site (`SIGDEP_SITE_CODE`) confirmé avec le PNLS — doit
  exister dans `core.sites`.
- ☐ Secret client Keycloak agent transmis au site par canal sécurisé.

## 5. Site pilote — installation agent

Choisir **un** mode parmi A / B / C selon le poste cible :

### Mode A — systemd (Linux)
- ☐ Fat-jar récupéré (release GitHub ou build local).
- ☐ Unit `sigdep-sync.service` installée + `.env` rempli.
- ☐ `systemctl enable --now sigdep-sync` OK.

### Mode B — Docker
- ☐ `docker-compose.site.yml` copié, scénario réseau choisi (host /
  réseau Docker existant / machine distante) décommenté.
- ☐ `.env` rempli, `docker compose up -d` OK.

### Mode C — Windows (WinSW)
- ☐ ZIP `sigdep-sync-windows-<version>.zip` téléchargé depuis la
  release GitHub.
- ☐ Extrait dans `C:\sigdep-sync\` (chemin **sans espaces ni accents**).
- ☐ `.env` créé en **UTF-8** à partir de `sigdep-sync.env.example`.
- ☐ `install-service.bat` lancé en administrateur, service démarré.

**Livrable commun** : `curl http://localhost:8080/actuator/health` (ou
équivalent Windows) retourne `UP` sur le poste agent.

## 6. Site pilote — validation bout-en-bout

À faire dans cet ordre, sans sauter d'étape :

- ☐ **T+5 min** : logs agent montrent `OAuthAuthenticator … token acquired`.
- ☐ **T+10 min** : page **Synchronisation** côté console montre le site
  avec `last_seen` récent.
- ☐ **T+15 min** : premier cycle terminé, `core.patients` côté hub
  contient ≥ 1 patient du site.
- ☐ **T+1 h** : tous les extracteurs ont tourné au moins une fois
  (`patients`, `visits`, `initiations`, `closures`, `lab_results`,
  `tpt`, `hiv_screening`, `pmtct_*`).
- ☐ Compteurs cohérents : `mart.tx_curr` du site ≈ file active connue
  du site (±5 % de tolérance vu les écarts de définition).
- ☐ Aucun record en `DEAD_LETTER` après 24 h ; sinon ouvrir la page
  **Rejets** et triager.

## 7. Site pilote — formation + handover

- ☐ Session de 30 min avec le responsable de site (SITE_USER) :
  parcours de la console, lecture des indicateurs PEPFAR, page
  **Rejets** côté site.
- ☐ Document récapitulatif laissé au site : URL console, login,
  contact support, fréquence du cycle (15 min), où trouver les logs
  agent.
- ☐ Coordonnées du support 1er niveau communiquées.

## 8. Pilote — fin de phase

À l'issue de **2 semaines** d'opération nominale :

- ☐ Réunion de revue : volume ingéré, incidents, retours utilisateurs.
- ☐ Backlog des correctifs priorisé (v1.1).
- ☐ Décision go/no-go pour la vague suivante de sites.
- ☐ Si go : préparer le seed des nouveaux sites dans `core.sites` +
  créer les clients Keycloak agent correspondants.

---

## Annexe — artefacts disponibles à la date de tag

| Composant | Type | URL |
|-----------|------|-----|
| sigdep-sync (Linux/Docker) | Image GHCR | `ghcr.io/itech-ci/sigdep-sync:<version>` |
| sigdep-sync (Windows) | ZIP attaché à la release | `https://github.com/ITECH-CI/sigdep-sync/releases/tag/v<version>` |
| sigdep-hub (bundle déploiement) | Tarball attaché à la release | `https://github.com/ITECH-CI/sigdep-hub/releases/tag/v<version>` |
| sigdep-hub ingestion-api | Image GHCR | `ghcr.io/itech-ci/sigdep-ingestion-api:<version>` |
| sigdep-hub console-api | Image GHCR | `ghcr.io/itech-ci/sigdep-console-api:<version>` |
| sigdep-hub console-web | Image GHCR | `ghcr.io/itech-ci/sigdep-console-web:<version>` |
