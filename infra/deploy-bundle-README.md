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

## Sauvegardes (important)

Le dossier `scripts/` contient `backup-hub.sh` — sauvegarde Postgres
compressée + rotation. Le hub centralise les données de tous les sites :
**planifier une sauvegarde quotidienne dès l'installation**.

```bash
chmod +x scripts/backup-hub.sh
# dump complet (base métier + dashboards Superset + rôles)
./scripts/backup-hub.sh --scope full
# cron quotidien à 02h30, rétention 30 jours (défaut)
crontab -e
#   30 2 * * *  /opt/sigdep-hub/scripts/backup-hub.sh --scope full >> /var/log/sigdep-backup.log 2>&1
```

Répliquer les dumps hors-site (rsync/rclone/S3) et les chiffrer : ils
contiennent des données patients. Détails : `scripts/README.md`.

## Documentation complète

La procédure détaillée (clé JWT, premier SUPER_ADMIN, seed des sites,
maintenance, dépannage) vit dans le guide d'installation :

https://github.com/ITECH-CI/sigdep-hub/blob/master/docs/user-guide/deploiement/installer-hub.md
