# Scripts opérationnels — hub SIGDEP

## `import_realm.sh`

Importe (ou réimporte avec `--override`) le realm Keycloak SIGDEP à
partir de `infra/keycloak/realm-sigdep.json` dans le container
`sigdep-keycloak` en cours d'exécution.

```bash
./import_realm.sh             # idempotent
./import_realm.sh --override  # drop + re-import
```

À utiliser après avoir édité `realm-sigdep.json` (rôles, clients,
loginTheme, etc.) pour propager les changements sans redémarrer la
stack.

---

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
- Tout schéma externe à `core.*` et `audit.*` (Keycloak notamment)

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
  toucher au realm Keycloak ni au volume Postgres.
- Après une migration de schéma qui aurait dégradé l'intégrité des
  données.

### Quand **ne pas** l'utiliser

- **Ne pas confondre** avec `docker compose down -v` : un `down -v`
  efface tout le volume Postgres, donc **aussi Keycloak** (qui partage
  l'instance). `reset-hub.sh` est conçu précisément pour ne pas tomber
  dans ce piège.
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

## `scan_htmlforms.py` / `build_org_csvs.py`

Scripts Python utilitaires pour, respectivement :
- scanner les `.html` du dossier `docs/htmlforms/` et extraire les
  concepts openmrs référencés (utile pour aligner extracteurs et
  formulaires) ;
- construire les CSV de seed pour `core.regions / districts / sites`
  à partir d'une source nationale.

Voir l'en-tête de chaque script pour leur usage spécifique.
