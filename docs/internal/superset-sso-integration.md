# Intégrer Apache Superset avec SSO derrière une app — guide de référence

Ce document capitalise l'intégration de **Superset** comme outil d'analyse BI,
accessible **sans nouvelle authentification** (SSO) depuis une application web
existante qui possède sa propre auth (ici : SIGDEP-3, JWT maison). Il est rédigé
pour être **réutilisable sur d'autres projets**.

Chaque section donne le **quoi**, le **pourquoi**, et les **pièges** qui nous
ont coûté du temps — pour ne pas les refaire.

---

## 1. Architecture retenue : sous-domaine dédié (PAS sous-chemin)

**Superset sur son propre vhost** : `analytics.<domaine>` (ou
`analytics-<console>.<parent>`), **jamais** sous `https://<app>/analytics/`.

**Pourquoi.** Superset sert son interface, son API et ses assets **à la racine**
(`/api`, `/static`, `/login`, `/superset/...`). Sous un sous-chemin, ces routes
entrent en collision avec celles de l'app → page blanche, boucles de login,
assets 404. `SUPERSET_APP_ROOT` / `APPLICATION_ROOT` **ne suffisent pas**
(l'image 4.1.x les ignore largement). Un vhost dédié élimine toute collision.

**Piège « niveau de sous-domaine » (certificat).**
- `analytics.<console>` (point) = un niveau de plus → un wildcard `*.<parent>`
  ne le couvre PAS si `<console>` est déjà un sous-domaine
  (ex. `*.itech-civ.org` **ne couvre pas** `analytics.sigdephub-v3.itech-civ.org`).
- `analytics-<console>` (tiret) = **même niveau** que la console → couvert par
  le wildcard existant `*.<parent>`. **Pratique pour réutiliser un cert en place.**

---

## 2. Image Superset = officielle + driver PostgreSQL

L'image `apache/superset:<tag>` n'embarque **aucun driver de base tierce**.
Sans driver, impossible de connecter PostgreSQL (ni la base métier, ni les
métadonnées).

```dockerfile
FROM apache/superset:4.1.1
USER root
RUN pip install --no-cache-dir psycopg2-binary==2.9.10
USER superset
```

---

## 3. Métadonnées Superset sur PostgreSQL (pas SQLite)

SQLite (défaut) échoue sur les écritures concurrentes en multi-worker gunicorn
→ « Impossible de migrer l'état de l'éditeur de requêtes » en boucle dans SQL Lab.

- Créer une base + rôle dédiés (`superset_meta`, en écriture) au 1er boot Postgres.
- `superset_config.py` : `SQLALCHEMY_DATABASE_URI = postgresql://superset_meta:...`.

**Piège.** `CREATE DATABASE` ne peut pas être dans un bloc transactionnel/`DO`
→ le faire à part (`createdb`), idempotent (tester `pg_database` d'abord).

---

## 4. Base métier en lecture seule + droits via migrations

Rôle `superset_ro` (SELECT sur les schémas métier, **jamais** sur le schéma
d'auth qui contient hash de mots de passe / clés API).

**Piège majeur (ordre de création).** Le script d'init Postgres
(`docker-entrypoint-initdb.d/`) s'exécute **au 1er boot, sur volume vierge** —
les schémas métier **n'existent pas encore** (créés par les migrations de l'app,
après). Donc :
- Script init : crée **seulement le rôle** + `GRANT CONNECT`. PAS de GRANT sur
  les schémas (sinon `ERROR: schema "core" does not exist` → `set -e` →
  **Postgres exited(3)** au 1er `up`, ET le rôle n'a jamais les droits).
- Les `GRANT SELECT` sur les schémas : via une **migration** (Liquibase/Flyway),
  idempotente (`runAlways`), conditionnelle (`IF EXISTS (rôle)`), une fois les
  schémas créés.

**Piège bash.** Un `Write` qui recrée le script peut **perdre le bit `+x`** →
`/bin/bash: bad interpreter: Permission denied`. Vérifier `chmod +x` et que git
tracke le mode `100755`.

---

## 5. SSO « header de confiance » (cœur du sujet)

### Principe
1. Au login, l'app pose un **cookie sur le domaine PARENT**
   (`Domain=.<parent>`, `HttpOnly`, `Secure`, `SameSite=Lax`), contenant un
   jeton (ici le JWT d'accès). Ce cookie est envoyé aux DEUX sous-domaines
   (app + analytics).
2. nginx (vhost analytics) fait un **`auth_request`** vers un endpoint de l'app
   (`/api/auth/verify`) qui lit le cookie, valide, et renvoie `200 + en-têtes
   d'identité` (`X-Remote-User`, `X-Remote-Role`) ou `401`.
3. nginx **injecte** ces en-têtes vers Superset (en écrasant toute valeur
   entrante : anti-forge).
4. Superset (`AUTH_REMOTE_USER` + security manager custom) connecte
   l'utilisateur, le crée au besoin, mappe son rôle.

### Contrainte de domaine
L'app et Superset doivent partager un **domaine parent commun**. Le cookie est
posé sur ce parent. **`.localhost` ne marche PAS** (TLD spécial, non partagé
entre sous-domaines) → en dev, tester sur **`lvh.me`** (résout vers 127.0.0.1,
vrai domaine : `app.lvh.me` + `analytics.lvh.me`, cookie `.lvh.me`).

### Mapping des rôles (security manager)
- Chercher l'utilisateur par **username OU email** : l'admin Superset bootstrap
  peut déjà exister avec le même email (`SUPERSET_ADMIN_EMAIL ==
  APP_ADMIN_EMAIL`) → sinon `UNIQUE constraint failed: ab_user.email` →
  **boucle de redirection**.
- `load_user` **robuste** : un cookie de session pointant un user supprimé
  (base meta réinitialisée) fait `None.is_active` dans FAB → **500**. Surcharger
  `load_user` pour renvoyer `None` proprement.

---

## 6. Les POST qui échouent en 403 — LE piège le plus coûteux

Symptôme : les GET marchent (interface visible), mais **tous les POST** (créer
un dataset, un chart, SQL Lab, `/superset/log/`) → **403**. La cause est une
**chaîne** de 4 problèmes imbriqués — les voici dans l'ordre où il faut les
résoudre.

### 6.a — L'API REST n'est pas authentifiée par AUTH_REMOTE_USER
FAB ne connecte l'utilisateur `REMOTE_USER` que via sa **vue de login web**,
**jamais** sur les appels `/api/v1/*`. Donc `/api/v1/security/csrf_token/`
renvoie **401** → le front ne récupère jamais le jeton CSRF → tous les POST
échouent en « CSRF token is missing ».

**Fix** : un `before_request` (dans `FLASK_APP_MUTATOR`) qui connecte
l'utilisateur depuis `X-Remote-User` sur **chaque** requête (API comprise).

### 6.b — Ne PAS régénérer la session à chaque requête
Si le `before_request` appelle `login_user` à chaque fois, il **régénère l'id de
session** (anti-fixation Flask-Login) → le jeton CSRF récupéré juste avant
devient invalide au POST suivant. **Symptôme** : dans les en-têtes navigateur,
`x-csrftoken` et `session` ont des estampilles différentes.

**Fix** : `login_user` UNIQUEMENT si aucune session n'existe (`"_user_id" not in
session`) ; sinon poser seulement `g.user`.

### 6.c — Cookie de session SameSite
Superset réimpose `SESSION_COOKIE_SAMESITE=Lax` **APRÈS** `superset_config.py`.
En `Lax`, le cookie n'est pas renvoyé sur les requêtes AJAX cross-contexte
(sous-domaine) → jeton CSRF absent. **Fix** : forcer `SameSite=None` + `Secure`
(HTTPS) dans `FLASK_APP_MUTATOR` (exécuté en dernier). Piloter par une variable
(`None+Secure` en prod HTTPS, `Lax` en dev HTTP — `None` exige `Secure`, donc
impossible en HTTP).

> NE PAS désactiver la CSRF (`WTF_CSRF_ENABLED=False`) : ça casse l'API REST FAB
> qui se rabat alors sur JWT → « Missing Authorization Header ».

### 6.d — LA cause finale : Origin propagé au auth_request
Même tout corrigé, les POST navigateur restaient en **403**, **log Superset
VIDE** → le 403 venait de **nginx**, pas de Superset.

Méthode de diagnostic décisive : **rejouer le POST en ajoutant les en-têtes du
navigateur un par un**. Résultat : **seul `Origin` fait basculer 400 → 403**.

Le navigateur envoie toujours `Origin` sur les POST (jamais sur les GET → d'où
« GET ok / POST KO », et « curl ok / navigateur KO »). Le `auth_request`
propageait cet `Origin` (`https://analytics-<host>`) au sous-appel vers l'app
(`/api/auth/verify`). Le **CORS de l'app** (qui ne connaît que le domaine de la
console) rejette cet Origin → **403** → l'`auth_request` échoue → nginx renvoie
403 sur **tout** POST.

**Fix** (le bon, prouvé) — neutraliser `Origin`/`Referer` dans le sous-appel
interne (serveur-à-serveur, ces en-têtes n'y ont pas leur place) :

```nginx
location = /sso-auth {
    internal;
    proxy_pass http://app_api/api/auth/verify;
    proxy_pass_request_body off;
    proxy_set_header Content-Length "";
    proxy_set_header Host    $http_host;
    proxy_set_header Cookie  $http_cookie;
    proxy_set_header Origin   "";   # <-- sinon CORS de l'app rejette → 403 POST
    proxy_set_header Referer  "";
}
```

**Test de confirmation** (sans déployer) :
```bash
# verify SANS Origin → 200 ; AVEC Origin → 403 = CORS de l'app rejette
curl -sI .../api/auth/verify -b "sso_cookie=..."
curl -sI .../api/auth/verify -b "sso_cookie=..." -H "Origin: https://analytics-<host>"
```

---

## 7. nginx : autres points

- **Résolution paresseuse de l'upstream Superset** (`resolver 127.0.0.11` +
  variable dans `proxy_pass`) : sinon nginx refuse de démarrer si Superset
  n'est pas déployé (« host not found in upstream »).
- **`/static/` exclu de l'`auth_request`** : sinon les assets sont redirigés
  vers le login quand non connecté → page cassée.
- **Visiteur non authentifié** → `error_page 401 = @anon` qui redirige vers le
  login de l'app (Superset est en `AUTH_REMOTE_USER`, il n'a pas de formulaire).
- **`server_name analytics.* "~^analytics-"`** : accepte les conventions point
  ET tiret.
- **Redirection du host console** dérivée du host analytics via une `map`
  (`analytics.X → X`, `analytics-X → X`).

---

## 8. Healthchecks (démarrage à froid fiable)

- **Postgres** : healthcheck `pg_isready`. Sinon les API/Superset démarrent
  avant que Postgres accepte les connexions → crash (`exited 1`).
- **API consommatrices** : `depends_on: { postgres: { condition:
  service_healthy } }`. Et console-api attend `ingestion-api` healthy (c'est lui
  qui applique les migrations → évite « missing table » au boot vierge).
- **console-web (nginx du SPA)** : healthcheck sur **`127.0.0.1`** et NON
  `localhost` (sur Alpine, `localhost`→`::1` IPv6 alors que la conf écoute en
  IPv4 → « Connection refused » → conteneur `unhealthy`).

---

## 9. Healthcheck applicatif et SMTP

Le `mailHealthIndicator` de Spring Actuator teste le SMTP à **chaque**
`/actuator/health`. Un SMTP injoignable (ou trust manquant) → health `DOWN` →
conteneur `unhealthy` → blocage du déploiement. **Désactiver** :
`management.health.mail.enabled=false`. (Office365 exige aussi
`MAIL_SSL_TRUST=smtp.office365.com` et `FROM == compte authentifié`.)

---

## 10. Versioning / footer (bonus)

- pom multi-module : `<version>${revision}</version>` + **flatten-maven-plugin**
  → `mvn package -Drevision=<tag>`. Piège : un build d'un module isolé doit
  installer le **POM parent** (`-pl <module> -am`), sinon résolution échoue
  (`parent:pom (absent)`).
- Front : `define` Vite inline `__APP_VERSION__/__APP_COMMIT__/__APP_BUILD_DATE__`
  ; fallback `package.json` en dev.
- CI : `-Drevision=<version>` (Maven) + build-args `APP_VERSION/COMMIT/DATE`.

---

## 11. Déploiement d'un tag réécrit

Pour une démo, réécrire le **même** tag (`gh release delete --cleanup-tag` puis
re-push) est acceptable (image non distribuée ailleurs) — mais NON en prod
réelle (un tag = un état figé immuable).

- L'étape « Create GitHub Release » peut échouer en **401 transitoire** sur un
  tag réécrit → un simple **rerun** du workflow suffit.
- Les fichiers montés en volume (`superset_config.py`, `nginx.prod.conf`, script
  init) viennent du **bundle** → **re-télécharger le bundle** et **recréer** le
  conteneur concerné (`--force-recreate`), pas seulement `pull`.
- Superset se **build localement** → `compose build superset` puis
  `pull --ignore-buildable` (sinon `pull access denied sigdep-superset`).
- Vider les **cookies navigateur** après chaque changement de session/secret.

---

## Checklist de reprise sur un nouveau projet

- [ ] Superset sur sous-domaine dédié (convention tiret si cert wildcard existant)
- [ ] Image dérivée + `psycopg2-binary`
- [ ] Base `superset_meta` (métadonnées) + rôle `superset_ro` (lecture seule)
- [ ] GRANT lecture seule via migration (pas le script init)
- [ ] Cookie SSO sur domaine parent (`.<parent>`), tester sur `lvh.me` en dev
- [ ] `auth_request` → endpoint `/verify` de l'app, **Origin/Referer neutralisés**
- [ ] `before_request` qui login depuis `X-Remote-User` (login_user si pas de session)
- [ ] `SameSite=None`+`Secure` via `FLASK_APP_MUTATOR` (prod HTTPS)
- [ ] CORS de l'app : ajouter le sous-domaine analytics SI le verify doit voir l'Origin (sinon neutraliser, recommandé)
- [ ] Healthchecks Postgres + console-web (127.0.0.1) + depends_on service_healthy
- [ ] `management.health.mail.enabled=false`
