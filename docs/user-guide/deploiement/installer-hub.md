# Installer le hub SIGDEP

Ce guide s'adresse à l'équipe qui déploie le hub central (PNLS / SIGDEP).
À la fin, vous avez une stack `postgres + keycloak + ingestion-api +
console-api + nginx` qui répond sur une URL publique.

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
       ┌─────────────────┼──────────────────────┬───────────────┐
       ▼                 ▼                      ▼               ▼
 ┌──────────────┐  ┌────────────┐       ┌──────────────┐  ┌──────────┐
 │ console-web  │  │ console-api│       │ingestion-api │  │ Keycloak │
 │  (SPA nginx) │  │ /api/      │       │ /api/v1/sync │  │ /realms/ │
 └──────────────┘  └─────┬──────┘       └──────┬───────┘  └────┬─────┘
                         ▼                     ▼               ▼
                       Postgres            Postgres        Postgres
```

Tout est servi via le nginx front pour exposer un **seul** origin :
`https://sigdep.pnls.ci/`. Les agents et la console parlent à cet
origin ; nginx route en interne vers les 4 conteneurs (`console-web`
sert le SPA, `console-api` les écrans, `ingestion-api` les batches
des agents, `keycloak` l'authentification).

## Étape 1 — Télécharger le bundle de déploiement

À chaque release `v*.*.*`, la CI publie sur la page Releases du dépôt
`sigdep-hub` une archive `sigdep-hub-deploy-<version>.tar.gz` qui
contient **tout** ce qu'il faut côté serveur : `docker-compose.yml`,
configuration nginx, realm Keycloak, thème SIGDEP, `.env.example`
avec les bons tags d'images déjà pré-renseignés. **Aucun clone git
n'est nécessaire** — vous n'avez à manipuler que ce dossier.

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
├── keycloak/
│   ├── realm-sigdep.json   # importé au premier démarrage
│   ├── entrypoint.sh       # substitue les placeholders avant import
│   └── themes/sigdep/      # thème de connexion SIGDEP
├── postgres/
│   └── 01-init-keycloak.sh # crée la base/user keycloak au 1er boot
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

# Keycloak
KEYCLOAK_ADMIN_PASSWORD=<mot_de_passe_fort>
KC_DB_PASSWORD=<mot_de_passe_fort>
KC_HOSTNAME=https://sigdep.pnls.ci
PUBLIC_ORIGIN=https://sigdep.pnls.ci

# Console-api → Keycloak Admin API (à régénérer en Étape 5)
KEYCLOAK_ADMIN_CLIENT_SECRET=changeme
```

Les tags d'images (`CONSOLE_API_IMAGE`, `INGESTION_API_IMAGE`,
`CONSOLE_WEB_IMAGE`) sont déjà pré-remplis dans `.env.example` avec
la version du bundle — ne pas y toucher sauf besoin spécifique.

> **Points d'attention sur les mots de passe** :
>
> - `KC_HOSTNAME` et `PUBLIC_ORIGIN` doivent être **identiques** et
>   correspondre à l'URL publique exacte (avec `https://`, sans slash
>   final) : c'est la clé qui dit à Keycloak quels redirect URIs
>   accepter pour le SPA.
> - Évitez les caractères `!`, `$`, `` ` ``, `\` dans les mots de
>   passe : ils sont interprétés par le shell quand vous lancez
>   `docker compose`. Si nécessaire, encadrez la valeur avec des
>   guillemets simples dans `.env` : `POSTGRES_PASSWORD='mot!passe'`.
> - La base et l'utilisateur Keycloak (`keycloak`/`keycloak`) sont
>   créés **automatiquement** au premier démarrage de Postgres par
>   `postgres/01-init-keycloak.sh`, en utilisant `KC_DB_PASSWORD`.
>   Aucune commande `psql` manuelle n'est nécessaire.

## Étape 4 — Démarrer la stack

```bash
docker compose --env-file .env up -d
```

Au premier démarrage :

- **Postgres** se crée et Liquibase exécute les migrations (création
  du schéma `core` + `audit`, seeds des régions / districts / sites
  / identifier_types).
- **Keycloak** importe le realm `sigdep` depuis
  `keycloak/realm-sigdep.json` (rôles, clients, thème SIGDEP).
- **ingestion-api** et **console-api** se connectent à Postgres.
- **nginx** termine la TLS et route les requêtes.

Vérifier :

```bash
docker compose ps
docker compose logs -f
```

Tous les services doivent passer à `healthy` en 2-3 minutes.

## Étape 5 — Configurer le client Keycloak admin

L'`KEYCLOAK_ADMIN_CLIENT_SECRET` doit correspondre au client
`sigdep-console-admin` du realm. Après le premier démarrage :

1. Ouvrir `https://sigdep.pnls.ci/admin` (interface Keycloak admin).
2. Se connecter avec `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`.
3. Sélectionner le realm **sigdep**.
4. Ouvrir **Clients → sigdep-console-admin → Credentials**.
5. Régénérer le secret et le copier dans `.env`
   (`KEYCLOAK_ADMIN_CLIENT_SECRET`).
6. Redémarrer console-api :
   `docker compose restart console-api`.

## Étape 6 — Créer le premier SUPER_ADMIN

Via l'admin Keycloak (`/admin`) :

1. Realm `sigdep` → **Users** → **Add user**.
2. Username : `pkomena` (par exemple), email obligatoire.
3. **Credentials** → définir un mot de passe non temporaire.
4. **Role mapping** → assigner `SUPER_ADMIN`.

Vous pouvez maintenant ouvrir `https://sigdep.pnls.ci/` et vous
connecter avec ce compte. Les comptes suivants se créent via la
page **Utilisateurs** de la console — voir
[admin/gestion-utilisateurs.md](../admin/gestion-utilisateurs.md).

## Étape 7 — Préparer l'enregistrement des sites

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

## Étape 8 — Déployer le premier agent

Voir [installer-agent.md](installer-agent.md).

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
Postgres et donc aussi le realm Keycloak.

## En cas de problème

### Un service ne démarre pas

```bash
cd /opt/sigdep-hub
docker compose logs <service>
```

### Keycloak n'accepte pas les redirects

Vérifier `KC_HOSTNAME` dans `.env` — il doit correspondre exactement
à l'URL publique. Toute modification nécessite un restart Keycloak.

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
