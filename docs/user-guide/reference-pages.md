# Référence — Pages de la console

La console SIGDEP-3 est organisée en quatre sections dans la barre
latérale : **Vue**, **Explorer**, **Données**, **Admin**. Cette page
décrit chaque écran. Le périmètre géographique appliqué dépend de votre
rôle — voir [reference-roles.md](reference-roles.md).

---

## Vue

### `/app/vue-ensemble` — Vue d'ensemble

Tableau de bord d'entrée. Présente :

- **4 KPIs principaux** : File active, TX_NEW du mois, % CV supprimée,
  Sites en ligne / total.
- **Graphe « File active sur 12 mois glissants »** — courbe mensuelle
  des patients actifs.
- **Zone Alertes** — sites > 7 j sans sync, sites > 24 h sans sync,
  dernier batch reçu.
- **Répartition par région** — diagramme en barres horizontales trié
  par file active (visible uniquement si votre périmètre couvre
  plusieurs régions).

Filtre disponible : sélecteur géographique (Région → District → Site).

### `/app/pepfar` — Indicateurs PEPFAR

Cascade complète des indicateurs MER. Sélecteur trimestriel
(FY × Q1-Q4) en haut à droite.

**KPIs (8 cartes)** :

- **Cascade TX** : TX_NEW, TX_CURR, TX_PVLS (D), TX_PVLS (%).
- **Cascade HTS / PMTCT / TB** : HTS_TST, HTS_POS + positivité,
  PMTCT_ART %, TB_PREV %.

**File active par modèle de soin** (donut) : Standard / IVSA / Échec
thérapeutique.

**Tableaux désagrégés** par tranche d'âge × sexe pour chacun :
TX_NEW, TX_CURR, TX_PVLS, HTS, PMTCT_STAT, PMTCT_ART, PMTCT_EID, TB_PREV.

Export CSV : `pepfar-FY<année>Q<n>.csv`, format `indicateur;sexe;tranche_age;valeur`.

Voir [reference-indicateurs.md](reference-indicateurs.md) pour les
définitions exactes.

---

## Explorer

### `/app/patients` — Patients

Liste paginée et triable de tous les patients du périmètre actif.
Colonnes : code patient, sexe, date de naissance, site.

Cliquez sur un patient pour ouvrir sa **page de détail** (`/app/patients/<id>`) :

- **Identité** : sexe, naissance, profession, niveau d'éducation,
  statut matrimonial, lieu de naissance, religion, identifiants
  (UPID, CODE ARV, CMU…).
- **Chronologie** : toutes les visites, initiations, clôtures et
  examens de laboratoire du patient, par ordre chronologique inverse.

### `/app/sites` — Sites

Liste des établissements du périmètre. Pour chaque site : code,
nom, région, district, dernière synchronisation, statut.

---

## Données

### `/app/clinique` — Suivi clinique

Page éclatée en **4 onglets** suivant le parcours patient :

- **Initiations** — fiche initiale ARV. KPIs (total, période,
  pédiatriques, référés), évolution annuelle, distribution porte
  d'entrée / régime initial / stade OMS, listing avec Type VIH.
- **Visites** — visites cliniques de suivi. Distributions stades OMS,
  dépistage TB, régimes ARV. Diagramme en barres mensuel **Visites vs
  dispensations ARV**. Courbe **Patients attendus vs venus** par mois
  (détecte les perdus de vue précoces). Listing détaillé.
- **IVSA** — sous-module patients non stables. KPIs (visites IVSA,
  succès confirmés, avec signes d'alerte), répartition MSD, listing.
- **Clôtures** — sorties de cohorte. KPIs (clôtures, décès, %
  mortalité), distributions par motif et causes de décès, listing
  avec dates transfert / décès.

Chaque onglet a son propre export CSV.

### `/app/pharmacie` — Pharmacie / ARV

Suivi des dispensations. KPIs cumul / période, distribution par
régime ARV, listing détaillé. Export CSV.

### `/app/depistage` — Dépistage

Module de dépistage VIH (anonyme — pas de lien patient_id). KPIs (cumul,
dépistés, positifs, positivité), évolution annuelle, distributions
(résultat final, population, motif, sexe). **Section dédiée Porte
d'entrée** avec contribution % et positivité par point d'accès. Listing.

### `/app/ptme` — PTME

**Deux onglets** :

- **Mère** : femmes enceintes suivies, couverture dépistage conjoint,
  positifs conjoints, distributions issues grossesse / statut ARV.
- **Enfant** : enfants exposés, prophylaxie ARV, résultats PCR1/2/3
  et sérologie. Badges POS/NEG/IND.

Export CSV par onglet.

### `/app/tpt` — TPT (Traitement Préventif Tuberculose)

KPIs (cumul, démarrés, terminés, % complétion), évolution annuelle,
distributions statut / protocole / résultats / observance. Listing
avec patient cliquable.

### `/app/biologie` — Biologie

Examens de laboratoire (CD4 absolu et %, charge virale). Désagrégation
par patient cliquable.

---

## Admin

### `/app/sync` — Synchronisation

**Deux onglets** :

- **Batches** — liste paginée des batches reçus avec horodatage, site,
  type d'entité, reçus / acceptés / rejetés, durée. Filtres scope +
  période.
- **Rejets** — enregistrements rejetés par le hub avec code d'erreur,
  message détaillé, source UUID. Workflow : voir
  [admin/investiguer-rejets.md](admin/investiguer-rejets.md).

### `/app/users` — Utilisateurs

Réservé `SUPER_ADMIN` et `IT_ADMIN`. Permet de créer, désactiver,
modifier les utilisateurs Keycloak et leurs rôles / périmètres.

Voir [admin/gestion-utilisateurs.md](admin/gestion-utilisateurs.md).

---

## Conventions transverses

### Sélecteur géographique

Présent en haut à droite de toutes les pages. Cascadé : Région →
District → Site. Resserre le périmètre en plus de votre rôle
(vous ne pouvez jamais élargir au-delà de votre rôle).

### Sélecteur de période

Selon la page :

- Vue d'ensemble : pas de sélecteur, toujours 12 mois glissants
  + mois en cours.
- PEPFAR : FY × Q1-Q4 (année fiscale USAID, oct→sep).
- Pages thématiques : 12 / 24 / 60 mois glissants.

### Export CSV

Bouton en haut à droite de chaque page. Encodage UTF-8 avec BOM,
séparateur point-virgule, dates `YYYY-MM-DD`. Reflète le périmètre +
la période actifs au moment du clic.

### Tri des listings

Cliquez sur l'en-tête d'une colonne triable (signalée par des
flèches discrètes). Premier clic = tri descendant ; deuxième clic =
ascendant ; troisième clic = retour à l'ordre par défaut.
