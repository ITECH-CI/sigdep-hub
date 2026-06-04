# Configuration Superset pour SIGDEP-3.
#
# Superset est servi à la RACINE de son propre vhost (sous-domaine
# analytics.<host> en prod, analytics.localhost en dev). Aucun sous-chemin →
# aucune réécriture de préfixe : /api, /static, /login appartiennent à Superset.
import os

# Clé secrète (fournie par l'env, défaut dev). NE PAS réutiliser en prod.
SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "dev-only-change-me")

# Derrière le nginx front (TLS terminé en amont) : honorer X-Forwarded-Proto
# et X-Forwarded-Host pour générer des URLs absolues correctes.
# x_port=0 : le host (X-Forwarded-Host) inclut déjà le port ; activer x_port
# ferait recombiner host+port par ProxyFix et perdrait le :9000 en dev
# (liens type /login?next=http://analytics.localhost/... sans port → refused).
ENABLE_PROXY_FIX = True
PROXY_FIX_CONFIG = {"x_for": 1, "x_proto": 1, "x_host": 1, "x_port": 0}
