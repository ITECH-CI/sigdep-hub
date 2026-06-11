# Installer un agent de synchronisation

Ce guide s'adresse aux **techniciens** qui installent l'agent
`sigdep-sync` sur le poste d'un site. À la fin, l'agent extrait les
données de l'OpenMRS local et les envoie au hub central toutes les
15 minutes.

Trois modes d'installation sont supportés ; choisissez selon le site :

- **Mode A — installeur Linux autonome** (recommandé sur serveur Linux).
  Bundle `tar.gz` avec **JRE embarqué** + service systemd, via `install.sh`.
  Aucun Java ni code source à installer.
- **Mode B — Docker** (si le site a déjà une stack Docker). Image
  pré-construite, aucun Java à installer sur l'hôte.
- **Mode C — installeur Windows autonome** (poste Windows). Archive `zip`
  avec **JRE embarqué** + service Windows (WinSW).

Tous les modes partagent les pré-requis ci-dessous et la validation côté hub à
la fin.

## Pré-requis communs

Sur le poste cible (site) :

- **Système** : Linux (Ubuntu 22.04+ ou équivalent) **ou** Windows
  10/11/Server 2016+ (64-bit). Le JRE est embarqué dans le bundle.
- **MySQL** local (= la base OpenMRS du site). L'agent y lit en
  lecture seule.
- **Accès réseau sortant** vers le hub central (HTTPS, port 443).

Côté hub (à demander à l'équipe SIGDEP centrale) :

- **Code du site** (`SIGDEP_SITE_CODE`) — identifiant unique du site
  dans `core.sites` (ex. `CHU_TREICHVILLE`).
- **Clé API du site** (`SIGDEP_API_KEY`) — générée depuis la console
  (page **Sites** → « Gérer » → « Générer une clé »), affichée une
  seule fois. C'est elle qui authentifie l'agent auprès du hub
  (en-tête `X-API-Key`).

## Préparer l'utilisateur MySQL en lecture seule

Cette étape est commune à tous les modes — l'agent ne doit jamais
écrire dans OpenMRS.

Sur l'OpenMRS local :

```sql
CREATE USER 'sigdep_reader'@'%' IDENTIFIED BY '<mot_de_passe>';
GRANT SELECT ON openmrs.* TO 'sigdep_reader'@'%';
FLUSH PRIVILEGES;
```

> Le `'%'` autorise les connexions depuis n'importe quelle IP. Si
> votre MySQL est dédié au site et n'expose pas son port en dehors,
> c'est sans risque. Sinon, restreignez à `'localhost'` (mode A) ou
> à l'IP du container Docker (mode B).

---

# Mode A — Installeur Linux autonome (systemd, JRE embarqué)

Recommandé sur un serveur Linux. Le bundle `sigdep-sync-linux-<version>.tar.gz`
(attaché à chaque release) embarque le **JRE Temurin 17** : aucun Java à
installer, aucun code source. (Pour Windows, voir le **Mode C**.)

## A.1 — Installer

```bash
# Télécharger le bundle de la version voulue
VERSION=2.1.0
curl -fsSL -O \
  "https://github.com/ITECH-CI/sigdep-sync/releases/download/v${VERSION}/sigdep-sync-linux-${VERSION}.tar.gz"
tar -xzf sigdep-sync-linux-${VERSION}.tar.gz
cd sigdep-sync-linux-${VERSION}

# Installer (crée l'utilisateur système, /opt/sigdep-sync, le service systemd)
sudo ./install.sh
```

`install.sh` crée l'utilisateur `sigdep-sync`, copie le jar + le JRE dans
`/opt/sigdep-sync`, prépare le buffer `/var/lib/sigdep-sync`, et installe l'unité
systemd. Il est **idempotent** : relancé pour une mise à jour, il remplace le jar
et le JRE mais **conserve votre `.env`**.

Puis configurer et démarrer :

```bash
sudo nano /opt/sigdep-sync/.env
#   SIGDEP_SITE_CODE        identifiant du site (core.sites du hub)
#   SIGDEP_LOCAL_DB_*       connexion MySQL OpenMRS (lecture seule, voir plus haut)
#   SIGDEP_CENTRAL_API_URL  URL du hub (https://…)
#   SIGDEP_API_KEY          clé générée sur la console (page Sites)

sudo systemctl start sigdep-sync
sudo systemctl status sigdep-sync
sudo journalctl -u sigdep-sync -f
```

Vous devez voir, dans les premières secondes :

```
INFO  Agent configured for site '<code>'
INFO  Sync cycle started. N extractor(s) registered.
INFO  Flushed Y batch(es) for ENTITY ...
```

**Mise à jour** : extraire la nouvelle archive et relancer `sudo ./install.sh`,
puis `sudo systemctl restart sigdep-sync`. **Désinstaller** : `sudo ./uninstall.sh`
(ajouter `--purge` pour supprimer aussi la config et le buffer).

> **Windows** : même principe (bundle autonome, JRE embarqué, service natif) —
> voir le **Mode C** plus bas, qui détaille l'installation du service Windows.

---

# Mode B — Docker

L'image officielle est publiée sur GitHub Container Registry :
`ghcr.io/itech-ci/sigdep-sync:<tag>` (par exemple
`ghcr.io/itech-ci/sigdep-sync:1.0.3`).

## B.1 — Installer Docker

```bash
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER
```

Déconnectez-vous puis reconnectez-vous pour que l'appartenance au
groupe `docker` prenne effet.

## B.2 — Préparer le dossier d'installation

```bash
mkdir -p ~/sigdep-sync
cd ~/sigdep-sync
```

Téléchargez les deux fichiers de référence :

```bash
curl -O https://raw.githubusercontent.com/<owner>/sigdep-sync/master/deploy/docker-compose.site.yml
curl -O https://raw.githubusercontent.com/<owner>/sigdep-sync/master/deploy/.env.example
mv .env.example .env
```

> En production, copiez ces fichiers depuis votre propre dépôt
> ou depuis la livraison fournie par l'équipe SIGDEP.

## B.3 — Choisir le scénario réseau

`docker-compose.site.yml` contient **trois scénarios commentés** dans
le bloc `services.sigdep-sync` ; décommentez **un seul** :

- **Scénario 1** — OpenMRS tourne sur la machine hôte (MySQL écoute
  sur `localhost:3306` du serveur). Décommentez le bloc
  `extra_hosts: ["host.docker.internal:host-gateway"]`. Dans `.env`,
  réglez `SIGDEP_LOCAL_DB_URL=jdbc:mysql://host.docker.internal:3306/openmrs?...`.

- **Scénario 2** — OpenMRS est lui-même dans un container Docker sur
  la même machine. Décommentez le bloc `networks: [openmrs_default]`
  en remplaçant `openmrs_default` par le nom réel (visible via
  `docker network ls`). Dans `.env`,
  `SIGDEP_LOCAL_DB_URL=jdbc:mysql://<nom_du_container_mysql>:3306/openmrs?...`.

- **Scénario 3** — OpenMRS tourne sur une machine distante du LAN.
  Aucune configuration réseau spéciale, l'agent sort en bridge par
  défaut. Vérifiez : firewall ouvert sur le 3306 de la machine
  distante, `bind-address = 0.0.0.0` dans `my.cnf`, utilisateur
  `sigdep_reader` autorisé pour l'IP de l'agent (le `'%'` du SQL
  ci-dessus le fait par défaut). Dans `.env`,
  `SIGDEP_LOCAL_DB_URL=jdbc:mysql://<ip>:3306/openmrs?...`.

## B.4 — Configurer le .env

Ouvrez `.env` et complétez :

- `SIGDEP_SITE_CODE` — fourni par le hub.
- `SIGDEP_LOCAL_DB_URL` / USER / PASSWORD — selon le scénario réseau.
- `SIGDEP_CENTRAL_API_URL` — URL publique du hub.
- `SIGDEP_API_KEY` — clé API du site, générée dans la console (page
  Sites), fournie par le hub.
- `SIGDEP_SYNC_TAG` (optionnel) — pour épingler une version précise
  au lieu de `latest`.

## B.5 — Démarrer l'agent

```bash
docker compose -f docker-compose.site.yml up -d
docker compose -f docker-compose.site.yml logs -f
```

Vous devez voir les mêmes lignes que pour le mode systemd
(`Sync cycle started`, `Enqueued X records`, `Flushed Y batch(es)`).

> **Architecture** : l'image n'est publiée que pour **linux/amd64**. Le
> fichier `docker-compose.site.yml` force déjà `platform: linux/amd64`,
> donc un serveur Linux amd64 (cas de production) l'exécute nativement.
> Sur un poste arm64 (Mac Apple Silicon, utilisé pour un test), Docker
> Desktop l'exécute par émulation. Si vous voyez
> `no matching manifest for linux/arm64/v8`, vérifiez que la ligne
> `platform: linux/amd64` est bien présente dans le service.

> **Connexion à GHCR si l'image est privée** : si vous voyez
> `unauthorized: not authorized for ghcr.io/.../sigdep-sync`, l'image
> est privée et vous devez vous authentifier :
> ```bash
> echo "<personal_access_token>" | docker login ghcr.io -u <username> --password-stdin
> ```
> Le PAT doit avoir le scope `read:packages`. Côté hub, l'admin peut
> aussi rendre l'image publique (Packages → Settings → Change visibility).

---

# Mode C — Windows (service via WinSW)

Pour les sites qui exécutent SIGDEP-3 sur un poste Windows. L'agent
tourne comme service Windows natif (démarre au boot, restart automatique
sur crash, logs dans un dossier dédié). Une archive ZIP prête à
l'emploi est publiée à chaque tag de version.

## C.1 — Pré-requis Windows

- Windows 10 / 11 / Server 2016 ou plus récent (64-bit).
- **Droits administrateur** sur le poste pour installer le service.
- MySQL OpenMRS accessible localement ou via le LAN.
- Aucun Java à installer — le JRE Temurin 17 est embarqué dans l'archive.

## C.2 — Télécharger l'archive Windows

Aller sur la page **Releases** du dépôt `sigdep-sync` :
`https://github.com/<owner>/sigdep-sync/releases`. Choisir la version
souhaitée (ex. `v1.0.2`) et télécharger l'asset
`sigdep-sync-windows-<version>.zip`.

## C.3 — Extraire dans un chemin permanent

Extraire le ZIP dans un dossier permanent, par exemple :

```
C:\sigdep-sync\
```

> Éviter `Program Files` (écriture restreinte par Windows, complique
> les logs et le buffer SQLite). Choisir un chemin **sans accents** et
> **sans espaces** pour éviter des bugs WinSW connus.

## C.4 — Configurer le .env

Dans le dossier d'extraction :

1. Copier `sigdep-sync.env.example` en `.env`.
2. Éditer `.env` (Notepad, ou mieux Notepad++ en encodage UTF-8) :
   - `SIGDEP_SITE_CODE`
   - `SIGDEP_LOCAL_DB_PASSWORD`
   - `SIGDEP_CENTRAL_API_URL`
   - `SIGDEP_API_KEY`
   - `SIGDEP_BUFFER_PATH` (par défaut `C:\sigdep-sync\buffer.sqlite`,
     adapter si le dossier d'extraction est différent).

> **Encodage important** : ne **pas** sauvegarder `.env` en UTF-16 /
> Unicode (option par défaut de l'ancien Notepad). Utiliser UTF-8 ou
> ANSI. Sinon l'agent lira des caractères corrompus au démarrage.

## C.5 — Installer le service Windows

Clic droit sur **`install-service.bat`** → **« Exécuter en tant
qu'administrateur »**. Le script :

1. Vérifie la présence du `.env`.
2. Installe le service Windows nommé `sigdep-sync`.
3. Le démarre.
4. Affiche son statut.

Le service apparaît dans `services.msc` sous **« SIGDEP-3 Edge Sync
Agent »**.

## C.6 — Vérifier les logs

Les logs vont dans `<dossier_installation>\logs\` :

- `sigdep-sync.out.log` — stdout (logs applicatifs).
- `sigdep-sync.err.log` — stderr (erreurs JVM).

Suivre en live :

```
Get-Content -Wait C:\sigdep-sync\logs\sigdep-sync.out.log
```

Vous devez voir, dans les premières secondes :

```
INFO  Sync cycle started. N extractor(s) registered.
INFO  Enqueued X records (...)
INFO  Flushed Y batch(es) for ENTITY ...
```

---

# Validation finale (commune aux 3 modes)

## Valider côté hub

Demander à un administrateur du hub de vérifier que :

1. Le site apparaît dans **/app/sites** avec un statut « En ligne ».
2. La page **/app/sync** liste des batches récents pour ce site.
3. L'onglet **Rejets** ne montre pas une explosion de rejets.

Au premier cycle, l'agent peut envoyer plusieurs milliers
d'enregistrements (toute l'historique). C'est normal — laissez-le
travailler quelques heures, puis revérifiez.

## Mise à jour

### Mode A (systemd)

```bash
sudo systemctl stop sigdep-sync
sudo cp sigdep-sync-<nouvelle_version>.jar /opt/sigdep-sync/sigdep-sync.jar
sudo systemctl start sigdep-sync
sudo journalctl -u sigdep-sync -f   # vérifier le démarrage
```

### Mode B (Docker)

Si `SIGDEP_SYNC_TAG=latest` dans `.env` :

```bash
cd ~/sigdep-sync
docker compose -f docker-compose.site.yml pull
docker compose -f docker-compose.site.yml up -d
docker compose -f docker-compose.site.yml logs -f
```

Pour épingler une nouvelle version : éditer `.env`,
`SIGDEP_SYNC_TAG=1.0.2` (par exemple), puis les 3 commandes ci-dessus.

### Mode C (Windows)

1. Télécharger la nouvelle archive ZIP depuis la page Releases.
2. Dans le dossier d'installation, ouvrir un cmd en administrateur :
   ```
   sigdep-sync-service.exe stop
   ```
3. Remplacer **uniquement** `sigdep-sync.jar` et le dossier `jre\`
   par ceux de la nouvelle archive. **Garder** `.env` et
   `buffer.sqlite`.
4. Redémarrer :
   ```
   sigdep-sync-service.exe start
   ```

Dans tous les modes, **l'outbox SQLite et la watermark sont préservées**
(dossier `/var/lib/sigdep-sync` pour le mode A Linux, dossier configuré pour
Windows, volume Docker pour le mode B) :
l'agent reprend exactement où il en était.

## En cas de problème

### L'agent ne démarre pas

**Mode A** :
```bash
sudo journalctl -u sigdep-sync --no-pager | tail -50
```

Causes fréquentes :
- **`.env` incomplet** : variable obligatoire manquante (`SIGDEP_SITE_CODE`,
  `SIGDEP_LOCAL_DB_*`, `SIGDEP_API_KEY`…). Le bundle embarque le JRE, donc
  l'erreur n'est jamais « Java absent ».
- **`.env` non lisible** par l'utilisateur `sigdep-sync` : vérifier les
  permissions (`install.sh` le met en `640 sigdep-sync:sigdep-sync`).
- **Buffer non accessible** : vérifier `/var/lib/sigdep-sync` et son propriétaire.

**Mode B** :
```bash
docker compose -f docker-compose.site.yml logs --tail=100
```

Causes fréquentes :
- **Image non accessible** (`unauthorized`) : voir la note GHCR privé
  dans la section B.5.
- **`.env` malformé** : pas de guillemets autour des valeurs, pas
  d'espaces autour des `=`.
- **Volume non monté** : vérifier `docker volume ls | grep sigdep`.

**Mode C (Windows)** :
```
type logs\sigdep-sync.err.log
type logs\sigdep-sync.out.log
```

Causes fréquentes :
- **`install-service.bat` lancé sans droits admin** : refaire avec
  clic droit → « Exécuter en tant qu'administrateur ».
- **`.env` en UTF-16 / Unicode** : le ré-enregistrer en UTF-8 ou ANSI
  (avec Notepad++ par exemple).
- **Chemin avec espaces ou accents** : WinSW supporte mal `C:\Users\Mon Bureau\…` ;
  réinstaller dans `C:\sigdep-sync\`.
- **JRE corrompu** dans le ZIP : re-télécharger l'archive depuis la
  page Releases.

### L'agent démarre mais n'envoie rien

- **Vérifier la connectivité hub** : `curl -fsSL <SIGDEP_CENTRAL_API_URL>/actuator/health`.
- **Vérifier la clé API** : un push qui revient en `401` signale une
  `SIGDEP_API_KEY` absente, erronée ou révoquée — régénérer la clé
  dans la console (page Sites) et la recopier dans `.env`.
- **Vérifier MySQL local** :
  - Mode A : `mysql -u sigdep_reader -p openmrs -e 'SELECT 1'`.
  - Mode B : l'erreur de connexion apparaît dans les premières lignes
    des logs container ; sinon
    `docker exec sigdep-sync ...`.
  - Mode C : utiliser un client MySQL Windows
    (`mysql -u sigdep_reader -p openmrs -e "SELECT 1"`) ou tester via
    HeidiSQL / DBeaver avec les mêmes paramètres que ceux du `.env`.

### Beaucoup de rejets

Voir [admin/investiguer-rejets.md](../admin/investiguer-rejets.md).

### Reset complet de l'agent

- **Mode A / Mode B (Linux)** : lancer
  `sigdep-sync/scripts/reset-agent.sh` (voir
  `sigdep-sync/scripts/README.md`).
- **Mode C (Windows)** :
  ```
  sigdep-sync-service.exe stop
  del buffer.sqlite
  sigdep-sync-service.exe start
  ```

## Voir aussi

- [installer-hub.md](installer-hub.md) — déploiement côté central.
- `sigdep-sync/README.md` — documentation technique de l'agent.
- `sigdep-sync/packaging/systemd/sigdep-sync.service` — unit systemd
  de référence.
