# SIGDEP-3 hub — bundle de déploiement

Ce dossier contient la configuration nécessaire pour installer le hub
SIGDEP-3 sur un serveur Linux avec Docker.

## Procédure résumée

1. Copier `.env.example` en `.env` et renseigner les secrets
   (`POSTGRES_PASSWORD`, `SIGDEP_JWT_SECRET`, `SIGDEP_ADMIN_EMAIL`,
   `SIGDEP_ADMIN_PASSWORD`).
2. Déposer les certificats TLS dans `nginx/certs/` :
   `fullchain.pem` + `privkey.pem`.
3. `docker compose --env-file .env up -d`.

## Documentation complète

La procédure détaillée (clé JWT, premier SUPER_ADMIN, seed des sites,
maintenance, dépannage) vit dans le guide d'installation :

https://github.com/ITECH-CI/sigdep-hub/blob/master/docs/user-guide/deploiement/installer-hub.md
