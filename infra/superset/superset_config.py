# Configuration Superset pour SIGDEP-3.
#
# Sert Superset sous /analytics/ derrière le nginx front. Mécanique :
#   - nginx strippe le préfixe (proxy_pass avec slash final) et pose
#     X-Forwarded-Prefix: /analytics ;
#   - ProxyFix (x_prefix=1) applique ce préfixe à SCRIPT_NAME, donc Superset
#     sert à la racine mais génère liens/redirections/assets préfixés.
# Ne PAS ajouter APPLICATION_ROOT ni un middleware SCRIPT_NAME maison : cela
# double le préfixe → session incohérente → boucle de redirection.
import os

# Clé secrète (fournie par l'env, défaut dev). NE PAS réutiliser en prod.
SECRET_KEY = os.environ.get("SUPERSET_SECRET_KEY", "dev-only-change-me")

# Honorer les X-Forwarded-* de nginx, dont le préfixe (x_prefix).
ENABLE_PROXY_FIX = True
PROXY_FIX_CONFIG = {"x_for": 1, "x_proto": 1, "x_host": 1, "x_port": 1, "x_prefix": 1}
