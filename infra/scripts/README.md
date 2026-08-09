# Scripts opérationnels — hub SIGDEP

## `reset-hub.sh`

TRUNCATE les tables métier (`core.*`) et l'audit (`audit.*`) du hub, en
préservant les référentiels et l'état Liquibase.

### Ce qui est purgé

- `core.patients`, `core.patient_identifiers`, `core.visits`
- `core.treatment_initiations` (+ `_pediatric`)
- `core.closures`, `core.lab_results`, `core.tpt_records`
- `core.dispensations`, `core.screenings`
- `core.ptme_mothers` (+ `_visits`), `core.ptme_children` (+ `_visits`)
- `audit.sync_batch`, `audit.rejected_record`

### Ce qui est préservé

- `core.regions`, `core.districts`, `core.sites` (référentiels seedés)
- `core.identifier_types`
- `public.databasechangelog*` (état Liquibase intact)
- Le schéma `auth.*` (comptes, clés API) — non touché par le reset métier

### Usage

```bash
# Mode interactif — affiche les comptes avant et demande confirmation
./reset-hub.sh

# Non-interactif (ansible, CI)
./reset-hub.sh --yes

# Si le container Postgres a un nom non standard
./reset-hub.sh --container my-postgres --db sigdep_prod
```

### Quand l'utiliser

- En phase d'intégration / test, pour repartir d'un hub propre sans
  toucher aux comptes (`auth.*`) ni au volume Postgres.
- Après une migration de schéma qui aurait dégradé l'intégrité des
  données.

### Quand **ne pas** l'utiliser

- **Ne pas confondre** avec `docker compose down -v` : un `down -v`
  efface tout le volume Postgres, donc **aussi les comptes** (`auth.*`).
  `reset-hub.sh` est conçu précisément pour ne pas tomber dans ce piège.
- En production sans coordination préalable avec les sites : les
  agents continueront à pousser, le hub repartira de zéro mais avec
  un délai variable selon la latence des sites.

### Après le reset

Les agents en cours pointent toujours leurs watermarks sur des IDs
qui n'existent plus côté hub, mais les `(site_id, source_uuid)`
restent stables — le hub recréera les lignes au fur et à mesure que
les batches arrivent.

Pour forcer un agent terrain à **tout re-extraire** depuis openmrs
(pas seulement depuis sa dernière watermark), lance
`sigdep-sync/scripts/reset-agent.sh` côté terrain.

### Variables d'environnement reconnues

| Variable               | Défaut             | Rôle                     |
| ---------------------- | ------------------ | ------------------------ |
| `SIGDEP_DB_CONTAINER`  | `sigdep-postgres`  | Container Postgres       |
| `SIGDEP_DB_NAME`       | `sigdep`           | Base de données          |
| `SIGDEP_DB_USER`       | `sigdep`           | User Postgres            |

---

## `backup-hub.sh`

Sauvegarde la base Postgres du hub dans un **dump compressé horodaté**, avec
**rotation** (rétention en jours). C'est la sauvegarde de secours du hub — à
planifier en cron sur le serveur de production.

### Deux périmètres (`--scope`)

| Scope | Contenu | Quand |
| ----- | ------- | ----- |
| `sigdep` (défaut) | base `sigdep` seule : `core.*` (métier), `audit.*` (rejets), `auth.*` (comptes, clés API) | dump ciblé rapide du métier |
| `full` | **toute l'instance** via `pg_dumpall` : `sigdep` + `superset_meta` (**dashboards / datasets Superset**) + rôles Postgres | restauration à l'identique, déplacement de serveur |

> ⚠️ Le scope `sigdep` **n'inclut pas** les dashboards Superset (ils vivent dans
> la base `superset_meta`). Pour ne rien perdre lors d'un déplacement de base,
> utiliser `--scope full`. C'est le scope recommandé pour la sauvegarde
> quotidienne automatique.

### Usage

```bash
# Base métier seule, rétention 30 jours, dans /var/backups/sigdep
./backup-hub.sh

# Tout (Superset + rôles inclus) — recommandé pour le cron quotidien
./backup-hub.sh --scope full

# Répertoire et rétention personnalisés
./backup-hub.sh --scope full --dir /mnt/backups --retention 60
```

Sortie : `sigdep-<scope>-YYYY-MM-DD_HHMMSS.sql.gz`. Le dump est écrit dans un
fichier `.part` renommé seulement après succès + vérification (taille non nulle,
intégrité gzip) — jamais de dump tronqué d'apparence valide. La rotation ne
supprime que les dumps du **même scope**.

### Planification (cron)

```cron
# Dump complet quotidien à 02h30, journalisé
30 2 * * *  /opt/sigdep-hub/infra/scripts/backup-hub.sh --scope full \
            >> /var/log/sigdep-backup.log 2>&1
```

> **Copie hors-site.** Un dump sur le même serveur ne protège pas d'une perte du
> serveur. Répliquer `/var/backups/sigdep` vers un stockage distant (rsync,
> rclone, S3…) et **chiffrer** (le dump contient des données patients).

### Restauration

Voir `docs/DEPLOYMENT.md`, section « Disaster recovery » — procédure par scope.

### Variables d'environnement reconnues

| Variable                        | Défaut               | Rôle                     |
| ------------------------------- | -------------------- | ------------------------ |
| `SIGDEP_DB_CONTAINER`           | `sigdep-postgres`    | Container Postgres       |
| `SIGDEP_DB_NAME`                | `sigdep`             | Base (`--scope sigdep`)  |
| `SIGDEP_DB_USER`                | `sigdep`             | User Postgres            |
| `SIGDEP_BACKUP_DIR`             | `/var/backups/sigdep`| Répertoire des dumps     |
| `SIGDEP_BACKUP_RETENTION_DAYS`  | `30`                 | Rétention (jours)        |
| `SIGDEP_BACKUP_SCOPE`           | `sigdep`             | Scope par défaut         |

---

## `scan_htmlforms.py` / `build_org_csvs.py`

Scripts Python utilitaires pour, respectivement :
- scanner les `.html` du dossier `docs/htmlforms/` et extraire les
  concepts openmrs référencés (utile pour aligner extracteurs et
  formulaires) ;
- construire les CSV de seed pour `core.regions / districts / sites`
  à partir d'une source nationale.

Voir l'en-tête de chaque script pour leur usage spécifique.
