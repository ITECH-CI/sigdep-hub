# Installer le hub SIGDEP

Ce guide s'adresse à l'équipe qui déploie le hub central (PNLS / SIGDEP).
À la fin, vous avez une stack `postgres + ingestion-api + console-api +
console-web + nginx` qui répond sur une URL publique.

> **Auth v2.0** : l'authentification est assurée par Spring Security +
> JWT (HS256), intégrée à `console-api`. Plus de Keycloak : aucun
> serveur d'identité séparé, aucun realm à importer.

> **Pré-requis fonctionnels** : avoir validé la liste des régions /
> districts / sites à seeder dans `core.regions`, `core.districts`,
> `core.sites`. Les agents pourront s'y enregistrer ensuite.

## Pré-requis techniques

- **Serveur Linux** Ubuntu 22.04+ (ou équivalent) avec :
  - 4 vCPU minimum, 8 Go RAM.
  - 100 Go de stockage (croît avec les données ingérées).
  - Docker + docker compose v2.
- **Nom de domaine** (par exemple `sigdep.pnls.ci`) pointant sur l'IP
  publique du serveur.
- **Certificat TLS** (Let's Encrypt ou autorité officielle).

## Topologie

```
                  ┌─────────────┐
        :443 ─────│ nginx (TLS) │
                  └──────┬──────┘
                         │ (reverse-proxy en HTTP interne)
       ┌─────────────────┼──────────────────────┐
       ▼                 ▼                      ▼
 ┌──────────────┐  ┌────────────┐       ┌──────────────┐
 │ console-web  │  │ console-api│       │ingestion-api │
 │  (SPA nginx) │  │ /api/      │       │ /api/v1/sync │
 └──────────────┘  └─────┬──────┘       └──────┬───────┘
                         ▼                     ▼
                       Postgres            Postgres
```

Tout est servi via le nginx front pour exposer un **seul** origin :
`https://sigdep.pnls.ci/`. Les agents et la console parlent à cet
origin ; nginx route en interne vers les 3 conteneurs applicatifs
(`console-web` sert le SPA, `console-api` les écrans **et
l'authentification** `/api/auth/*`, `ingestion-api` les batches des
agents authentifiés par clé API).

## Étape 1 — Télécharger le bundle de déploiement

À chaque release `v*.*.*`, la CI publie sur la page Releases du dépôt
`sigdep-hub` une archive `sigdep-hub-deploy-<version>.tar.gz` qui
contient **tout** ce qu'il faut côté serveur : `docker-compose.yml`,
configuration nginx, `.env.example` avec les bons tags d'images déjà
pré-renseignés. **Aucun clone git n'est nécessaire** — vous n'avez à
manipuler que ce dossier.

Choisir la version sur https://github.com/ITECH-CI/sigdep-hub/releases
puis, sur le serveur :

```bash
VERSION=1.0.3   # remplacer par la version souhaitée
sudo mkdir -p /opt/sigdep-hub && cd /opt/sigdep-hub

curl -fsSL -o bundle.tar.gz \
  "https://github.com/ITECH-CI/sigdep-hub/releases/download/v${VERSION}/sigdep-hub-deploy-${VERSION}.tar.gz"

tar -xzf bundle.tar.gz --strip-components=1
rm bundle.tar.gz
```

Vous obtenez :

```
/opt/sigdep-hub/
├── docker-compose.yml      # stack prête à démarrer
├── .env.example            # à copier en .env et compléter
├── nginx/
│   ├── nginx.prod.conf     # configuration nginx
│   └── certs/              # à remplir avec fullchain.pem + privkey.pem
└── README.md
```

## Étape 2 — Déposer les certificats TLS

`nginx` veut **un seul fichier** `fullchain.pem` contenant la chaîne
complète (votre certificat suivi du / des intermédiaires de la CA),
plus la clé privée dans `privkey.pem`.

### Cas Let's Encrypt (certbot)

certbot produit déjà un `fullchain.pem` propre :

```bash
sudo ln -s /etc/letsencrypt/live/sigdep.pnls.ci/fullchain.pem \
           /opt/sigdep-hub/nginx/certs/fullchain.pem
sudo ln -s /etc/letsencrypt/live/sigdep.pnls.ci/privkey.pem \
           /opt/sigdep-hub/nginx/certs/privkey.pem
```

### Cas certificat wildcard (CA commerciale, ex. Sectigo)

Si vous avez un `.crt` (votre certificat seul) + un `.ca-bundle`
(intermédiaires), il faut reconstruire un fullchain en concaténant
les deux **avec un saut de ligne entre les deux** — sinon nginx
échoue avec `bad end line` :

```bash
sudo bash -c '
  cat /chemin/vers/itech-civ_org.crt > /opt/sigdep-hub/nginx/certs/fullchain.pem
  echo "" >> /opt/sigdep-hub/nginx/certs/fullchain.pem
  cat /chemin/vers/itech-civ_org.ca-bundle >> /opt/sigdep-hub/nginx/certs/fullchain.pem
'
sudo cp /chemin/vers/itech-civ.key /opt/sigdep-hub/nginx/certs/privkey.pem
sudo chmod 600 /opt/sigdep-hub/nginx/certs/privkey.pem
```

Vérifier que le fichier est bien formé (vous devez voir au moins 2
lignes `subject=...` : votre cert + l'intermédiaire) :

```bash
sudo openssl crl2pkcs7 -nocrl -certfile /opt/sigdep-hub/nginx/certs/fullchain.pem \
  | sudo openssl pkcs7 -print_certs -noout | grep subject
```

## Étape 3 — Configurer les secrets

```bash
cd /opt/sigdep-hub
cp .env.example .env
$EDITOR .env
```

Renseigner au minimum :

```ini
# Postgres
POSTGRES_PASSWORD=<mot_de_passe_fort>

# Auth v2.0 — URL publique (CORS) + clé de signature JWT + comptes admin
PUBLIC_ORIGIN=https://sigdep.pnls.ci
SIGDEP_JWT_SECRET=<chaîne aléatoire ≥ 32 octets>   # openssl rand -base64 48
# Deux comptes seedés au 1er boot : SUPER_ADMIN + IT_ADMIN.
SIGDEP_ADMIN_EMAIL=admin@pnls.ci
SIGDEP_ADMIN_PASSWORD=<mot_de_passe_fort>
SIGDEP_IT_ADMIN_EMAIL=it-admin@pnls.ci
SIGDEP_IT_ADMIN_PASSWORD=<mot_de_passe_fort>
```

Les tags d'images (`CONSOLE_API_IMAGE`, `INGESTION_API_IMAGE`,
`CONSOLE_WEB_IMAGE`) sont déjà pré-remplis dans `.env.example` avec
la version du bundle — ne pas y toucher sauf besoin spécifique.

> **Points d'attention** :
>
> - `SIGDEP_JWT_SECRET` doit faire **au moins 32 octets** et rester
>   secret. Le changer invalide tous les access tokens en cours (les
>   utilisateurs devront se reconnecter) — choisissez-le une fois.
> - `SIGDEP_ADMIN_EMAIL` / `SIGDEP_ADMIN_PASSWORD` ne servent qu'au
>   **tout premier démarrage** pour créer le compte SUPER_ADMIN
>   initial (si la table `auth.users` est vide). Une fois ce compte
>   créé, ces variables sont ignorées.
> - `PUBLIC_ORIGIN` doit correspondre à l'URL publique exacte (avec
>   `https://`, sans slash final) : elle pilote la politique CORS.
> - Évitez les caractères `!`, `$`, `` ` ``, `\` dans les mots de
>   passe : ils sont interprétés par le shell quand vous lancez
>   `docker compose`. Si nécessaire, encadrez la valeur avec des
>   guillemets simples dans `.env` : `POSTGRES_PASSWORD='mot!passe'`.

## Étape 4 — Démarrer la stack

```bash
docker compose --env-file .env up -d
```

Au premier démarrage :

- **Postgres** se crée et Liquibase exécute les migrations (création
  des schémas `core` + `audit` + `auth`, seeds des régions / districts
  / sites / identifier_types).
- **console-api** crée les comptes d'administration initiaux —
  **SUPER_ADMIN** (`SIGDEP_ADMIN_*`) et **IT_ADMIN** (`SIGDEP_IT_ADMIN_*`).
  Le seed est idempotent par email : un compte déjà présent n'est jamais
  réécrasé.
- **ingestion-api** et **console-api** se connectent à Postgres.
- **nginx** termine la TLS et route les requêtes.

Vérifier :

```bash
docker compose ps
docker compose logs -f
```

Tous les services doivent passer à `healthy` en 2-3 minutes.
Confirmer que les comptes admin ont bien été seedés :

```bash
docker compose logs console-api | grep -iE "SUPER_ADMIN|IT_ADMIN"
# → "Compte SUPER_ADMIN initial créé pour admin@pnls.ci"
# → "Compte IT_ADMIN initial créé pour it-admin@pnls.ci"
```

## Étape 5 — Se connecter et créer les comptes

Ouvrir `https://sigdep.pnls.ci/` et se connecter avec
`SIGDEP_ADMIN_EMAIL` / `SIGDEP_ADMIN_PASSWORD`. Les comptes suivants
se créent directement via la page **Utilisateurs** de la console
(rôle + zone d'intervention) — voir
[admin/gestion-utilisateurs.md](../admin/gestion-utilisateurs.md).

> Par sécurité, retirez `SIGDEP_ADMIN_PASSWORD` du `.env` après le
> premier démarrage (la variable n'est plus lue une fois le compte créé)
> et changez le mot de passe de l'admin depuis la console.

## Étape 6 — Préparer l'enregistrement des sites

Avant de déployer le premier agent, vérifier que tous les sites
ciblés sont présents dans `core.sites` :

```bash
docker exec sigdep-postgres psql -U sigdep -d sigdep \
  -c "SELECT code, name FROM core.sites ORDER BY code;"
```

Si un site manque, signaler le code et le nom à l'équipe SIGDEP : le
référentiel des sites est versionné côté code via une migration
Liquibase dédiée (`ingestion-api/src/main/resources/db/changelog/seed/`)
et sera livré dans le bundle de la release suivante. Ne pas écrire
en direct dans la table.

## Étape 7 — Déployer le premier agent

Chaque agent s'authentifie avec une **clé API** propre au site,
générée depuis la console (page **Sites** → bouton « Gérer » →
« Générer une clé »). La clé n'est affichée qu'une seule fois ;
copiez-la dans la config de l'agent (`SIGDEP_API_KEY`).
Voir [installer-agent.md](installer-agent.md).

## Étape 8 — Superset (« Analyses avancées », optionnel)

Le `docker-compose.prod.yml` inclut un service **Superset** servi sous
`https://<host>/analytics/`, plus un rôle PostgreSQL **lecture seule**
(`superset_ro`) créé au premier démarrage de la base.

1. Renseigner dans `.env` :
   ```ini
   SUPERSET_SECRET_KEY=<openssl rand -base64 42>
   SUPERSET_ADMIN_USERNAME=admin
   SUPERSET_ADMIN_PASSWORD=<mot_de_passe_fort>
   SUPERSET_ADMIN_EMAIL=admin@pnls.ci
   SUPERSET_DB_READONLY_PASSWORD=<mot_de_passe_fort>
   ```
2. Au premier démarrage, Superset initialise sa base de métadonnées et crée
   son compte admin ; le rôle `superset_ro` est créé (SELECT sur `core` +
   `analytics`, **jamais** sur `auth`).
3. Dans Superset (login séparé, l'admin ci-dessus) → **Settings → Database
   Connections → + Database**, ajouter la source SIGDEP :
   ```
   postgresql://superset_ro:<SUPERSET_DB_READONLY_PASSWORD>@postgres:5432/sigdep
   ```
4. Pour faire apparaître le menu **« Analyses avancées »** dans la console,
   l'image `console-web` doit être buildée avec la variable de dépôt
   `VITE_SUPERSET_URL` (ex. `https://<host>/analytics/`). Si elle est vide au
   build, le menu reste masqué (le reste de la console fonctionne normalement).

> **Note SSO** : en l'état, Superset a sa **propre** authentification (login
> distinct de la console). L'authentification unique (SSO) est prévue dans une
> phase ultérieure.

## Maintenance courante

### Sauvegarde Postgres

`docker exec sigdep-postgres pg_dump -U sigdep sigdep > backup-$(date +%F).sql`

À automatiser via cron + rotation. Conserver au moins 30 jours de
backups.

### Mise à jour de la stack

Deux cas de figure :

**Mise à jour mineure (nouveau tag d'images, pas de nouveaux fichiers
de conf)** — il suffit de bumper les tags dans `.env` puis :

```bash
cd /opt/sigdep-hub
docker compose --env-file .env pull
docker compose --env-file .env up -d
```

**Mise à jour majeure (nouveau bundle avec compose / nginx / realm
modifiés)** — télécharger le nouveau bundle à côté, comparer, fusionner :

```bash
cd /opt
VERSION=1.1.0   # remplacer
curl -fsSL -o sigdep-hub-new.tar.gz \
  "https://github.com/ITECH-CI/sigdep-hub/releases/download/v${VERSION}/sigdep-hub-deploy-${VERSION}.tar.gz"
tar -xzf sigdep-hub-new.tar.gz   # extrait sigdep-hub-deploy-${VERSION}/

# Comparer avec votre installation actuelle :
diff -r sigdep-hub/ sigdep-hub-deploy-${VERSION}/

# Mettre à jour les fichiers modifiés (sauf .env qui contient vos secrets).
# Puis :
cd sigdep-hub
docker compose --env-file .env pull
docker compose --env-file .env up -d
```

Liquibase appliquera automatiquement les nouvelles migrations au
démarrage de `ingestion-api`.

### Reset complet

**Ne jamais faire `docker compose down -v`** sur un environnement
qui contient des données qu'on veut garder. Ça purge le volume
Postgres (données ingérées **et** comptes utilisateurs).

## En cas de problème

### Un service ne démarre pas

```bash
cd /opt/sigdep-hub
docker compose logs <service>
```

### Connexion refusée / 401 sur toutes les requêtes

Vérifier que `SIGDEP_JWT_SECRET` est bien défini (≥ 32 octets) et
identique entre redémarrages — s'il change, tous les tokens émis
deviennent invalides. Vérifier aussi que `PUBLIC_ORIGIN` correspond à
l'URL d'accès (sinon CORS bloque les appels du SPA).

### Aucun compte pour se connecter

Si le log `console-api` indique que `auth.users` était vide mais
qu'aucun admin n'a été seedé, c'est que `SIGDEP_ADMIN_EMAIL` /
`SIGDEP_ADMIN_PASSWORD` n'étaient pas renseignés. Les définir dans
`.env` puis `docker compose restart console-api`.

### Postgres remplit le disque

Logique : SIGDEP-3 garde l'historique complet. Surveiller via
`docker exec sigdep-postgres df -h /var/lib/postgresql`.

Options : augmenter le disque, ou archiver et purger les anciennes
visites (à coordonner avec le PNLS).

## Voir aussi

- [installer-agent.md](installer-agent.md) — agent côté site.
- [pilote-checklist.md](pilote-checklist.md) — checklist de
  déploiement pour un pilote.
- Page Releases du repo : https://github.com/ITECH-CI/sigdep-hub/releases
