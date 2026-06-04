# Image Superset pour SIGDEP-3 = image officielle + driver PostgreSQL.
#
# L'image `apache/superset` n'embarque AUCUN driver de base tierce : sans ça,
# Superset ne peut pas se connecter à PostgreSQL (ni à la base SIGDEP, ni en
# base de métadonnées). On ajoute psycopg2-binary une fois pour toutes ici,
# plutôt qu'un `pip install` lent à chaque démarrage du conteneur.
FROM apache/superset:4.1.1

USER root
RUN pip install --no-cache-dir psycopg2-binary==2.9.10
USER superset
