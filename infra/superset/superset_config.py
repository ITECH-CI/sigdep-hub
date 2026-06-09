# Configuration Superset pour SIGDEP-3.
#
# Superset est servi à la RACINE de son propre vhost (sous-domaine
# analytics.<host> en prod, analytics.localhost en dev). Aucun sous-chemin →
# aucune réécriture de préfixe : /api, /static, /login appartiennent à Superset.
import logging
import os

from flask import request
from flask_appbuilder.security.manager import AUTH_REMOTE_USER
from superset.security import SupersetSecurityManager

log = logging.getLogger(__name__)

# Clé secrète (fournie par l'env, défaut dev). NE PAS réutiliser en prod.
SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "dev-only-change-me")

# Base de MÉTADONNÉES de Superset (dashboards, état SQL Lab, utilisateurs FAB…).
# PostgreSQL plutôt que SQLite (défaut) : SQLite échoue sur les écritures
# concurrentes en multi-worker → « Impossible de migrer l'état de l'éditeur de
# requêtes ». Si SUPERSET_METADATA_DB_URI est absent, on retombe sur le défaut.
_metadata_uri = os.environ.get("SUPERSET_METADATA_DB_URI")
if _metadata_uri:
    SQLALCHEMY_DATABASE_URI = _metadata_uri

# Interface en français par défaut (Superset est traduit via Flask-Babel).
# L'utilisateur peut toujours basculer la langue depuis son menu profil.
BABEL_DEFAULT_LOCALE = "fr"
LANGUAGES = {
    "fr": {"flag": "fr", "name": "Français"},
    "en": {"flag": "us", "name": "English"},
}

# Derrière le nginx front (TLS terminé en amont) : honorer X-Forwarded-Proto
# et X-Forwarded-Host pour générer des URLs absolues correctes.
# x_port=0 : le host (X-Forwarded-Host) inclut déjà le port ; activer x_port
# ferait recombiner host+port par ProxyFix et perdrait le :9000 en dev
# (liens type /login?next=http://analytics.localhost/... sans port → refused).
ENABLE_PROXY_FIX = True
PROXY_FIX_CONFIG = {"x_for": 1, "x_proto": 1, "x_host": 1, "x_port": 0}

# CSRF + session derrière le proxy. Les POST de SQL Lab (tabstateview,
# validate_sql, log…) échouaient en « CSRF token is missing » → 302 vers
# /login → page blanche / notifications d'erreur. Cause : le cookie de session
# Flask (qui porte le jeton CSRF) n'était pas renvoyé sur les requêtes AJAX
# cross-contexte derrière nginx, et la vérif Referer SSL stricte gênait.
#   - SESSION_COOKIE_SAMESITE=None + Secure : le cookie suit les requêtes AJAX
#     servies via le sous-domaine (obligatoire en HTTPS).
#   - WTF_CSRF_SSL_STRICT=False : ne pas rejeter sur un Referer reconstruit par
#     le proxy. La protection CSRF par jeton reste active.
# SESSION_COOKIE_SECURE doit être True en prod (HTTPS) — un cookie SameSite=None
# DOIT être Secure — mais False en dev (lvh.me en HTTP, sinon le cookie n'est
# jamais posé). Piloté par SUPERSET_SECURE_COOKIES (cf. compose).
_secure_cookies = os.environ.get("SUPERSET_SECURE_COOKIES", "true").lower() == "true"
WTF_CSRF_ENABLED = True
WTF_CSRF_SSL_STRICT = False
SESSION_COOKIE_SAMESITE = "None" if _secure_cookies else "Lax"
SESSION_COOKIE_SECURE = _secure_cookies
SESSION_COOKIE_HTTPONLY = True

# ===========================================================================
# SSO « header de confiance » avec la console SIGDEP.
#
# nginx (vhost analytics) vérifie le cookie sigdep_sso auprès de console-api
# (/api/auth/verify) et, si l'utilisateur est authentifié, injecte les en-têtes
# X-Remote-User (email) et X-Remote-Role (rôle SIGDEP). Le security manager
# ci-dessous connecte alors l'utilisateur, le crée au besoin, et mappe son rôle
# SIGDEP vers un rôle Superset. Si l'en-tête est absent (utilisateur non
# connecté côté console), Superset retombe sur son login classique.
#
# SÉCURITÉ : ce mécanisme N'EST SÛR QUE si Superset n'est joignable QUE via
# nginx (jamais en direct) et que nginx écrase tout X-Remote-* entrant — ce que
# fait le vhost analytics. Sinon, l'en-tête serait forgeable.
# ===========================================================================
AUTH_TYPE = AUTH_REMOTE_USER
AUTH_USER_REGISTRATION = True
AUTH_USER_REGISTRATION_ROLE = "Public"  # remplacé par le mapping ci-dessous

# Rôle SIGDEP (en-tête X-Remote-Role) → rôle Superset.
SIGDEP_ROLE_MAP = {
    "SUPER_ADMIN": "Admin",
    "IT_ADMIN": "Admin",
    "ANALYST": "Alpha",
    # Lecture seule pour tous les autres rôles consultatifs / zone-bound.
    "NATIONAL_VIEWER": "Gamma",
    "AUDITOR": "Gamma",
    "REGIONAL_COORD": "Gamma",
    "DISTRICT_COORD": "Gamma",
    "SITE_USER": "Gamma",
}
DEFAULT_SUPERSET_ROLE = "Gamma"

REMOTE_USER_HEADER = "X-Remote-User"
REMOTE_ROLE_HEADER = "X-Remote-Role"


class SigdepRemoteUserSecurityManager(SupersetSecurityManager):
    """Connecte l'utilisateur d'après les en-têtes injectés par nginx et garde
    son rôle Superset aligné sur son rôle SIGDEP à chaque requête."""

    def _sigdep_superset_role(self):
        sigdep_role = (request.headers.get(REMOTE_ROLE_HEADER) or "").strip()
        return SIGDEP_ROLE_MAP.get(sigdep_role, DEFAULT_SUPERSET_ROLE)

    def load_user(self, pk):
        # Robustesse : un cookie de session peut référencer un user_id qui
        # n'existe plus (base de métadonnées réinitialisée, utilisateur supprimé).
        # FAB ferait alors `None.is_active` → 500. On renvoie None proprement :
        # l'utilisateur sera ré-authentifié via l'en-tête X-Remote-User.
        try:
            return super().load_user(pk)
        except AttributeError:
            return None

    def auth_user_remote_user(self, username):
        # username = valeur de REMOTE_USER (posée par FAB depuis l'en-tête).
        if not username:
            return None
        target_role_name = self._sigdep_superset_role()
        role = self.find_role(target_role_name) or self.find_role(DEFAULT_SUPERSET_ROLE)

        # Chercher par username ET par email : l'admin Superset bootstrap peut
        # déjà exister avec le même email (ex. SUPERSET_ADMIN_EMAIL ==
        # SIGDEP_ADMIN_EMAIL). Créer un doublon → UNIQUE constraint sur
        # ab_user.email → échec de connexion → boucle de redirection.
        user = self.find_user(username=username) or self.find_user(email=username)
        if user is None:
            user = self.add_user(
                username=username,
                first_name=username,
                last_name="(SIGDEP)",
                email=username,
                role=role,
            )
            if not user:
                log.error("SSO: échec de création de l'utilisateur %s", username)
                return None
        else:
            # Réaligne le rôle à chaque connexion (le rôle SIGDEP fait foi).
            # NB : on NE touche PAS au compte si c'est déjà un Admin local
            # (admin bootstrap Superset) — on garde son accès complet.
            if role and not any(r.name == "Admin" for r in user.roles) \
                    and (len(user.roles) != 1 or user.roles[0].id != role.id):
                user.roles = [role]
                self.update_user(user)

        self.update_user_auth_stat(user)
        return user


CUSTOM_SECURITY_MANAGER = SigdepRemoteUserSecurityManager

# FAB lit REMOTE_USER depuis cet en-tête (posé par nginx auth_request).
AUTH_REMOTE_USER_ENV_VAR = "HTTP_X_REMOTE_USER"
