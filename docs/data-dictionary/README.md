# Dictionnaire de données SIGDEP-3

Le fichier **[`../SIGDEP3_dictionnaire_donnees.xlsx`](../SIGDEP3_dictionnaire_donnees.xlsx)**
décrit le schéma métier (`core.*`) du hub pour un public d'analystes /
statisticiens : une feuille par table, colonnes documentées (type, obligatoire,
clé/contrainte, description métier en français), plus une feuille « Notes
importantes » (doublon patient inter-sites, UPID, `voided`, dates manquantes,
`extra_data`, partitionnement…).

## Régénérer le dictionnaire

Le dico est produit par un script à partir d'un export du schéma réel de la
base de production (source de vérité, plus fiable que les migrations).

1. **Exporter le schéma** depuis la prod (inclut `visits`, partitionnée) :

   ```bash
   sudo docker exec sigdep-postgres psql -U sigdep -d sigdep -A -F ';' -c "
   SELECT c.table_name, c.ordinal_position, c.column_name,
     c.data_type || COALESCE('('||c.character_maximum_length||')','') ||
     COALESCE('('||c.numeric_precision||','||c.numeric_scale||')','') AS type_sql,
     c.is_nullable, c.column_default
   FROM information_schema.columns c
   WHERE c.table_schema='core'
     AND c.table_name IN ('regions','districts','sites','identifier_types','patients',
       'patient_identifiers','treatment_initiations','treatment_initiations_pediatric',
       'visits','dispensations','lab_results','closures','tpt_records','screenings',
       'ptme_mothers','ptme_mother_visits','ptme_children','ptme_child_visits')
   ORDER BY c.table_name, c.ordinal_position;" > schema_core.csv
   ```

2. **Générer le .xlsx** :

   ```bash
   pip install openpyxl
   python3 gen_dico.py      # écrit ../SIGDEP3_dictionnaire_donnees.xlsx
   ```

## Maintenir les descriptions

Les descriptions métier sont dans `gen_dico.py` :
- `TABLES` : ordre + libellé + description de chaque table (feuille Sommaire) ;
- `DESC` : description par colonne, clé `(table, colonne)` ;
- `COMMON` : descriptions des colonnes communes (id, site_id, voided…) ;
- `CONSTRAINTS` : clés/unicité au-delà de la PK ;
- la liste `notes` : la feuille « Notes importantes ».

Quand le schéma évolue (nouvelle colonne/table), ré-exporter `schema_core.csv`,
ajouter la description dans `DESC`, puis régénérer.