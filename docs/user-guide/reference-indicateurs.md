# Référence — Indicateurs

Cette page documente les **définitions exactes** des indicateurs
calculés par SIGDEP-3. Ces définitions s'inspirent du PEPFAR MER 2.7
et restent compatibles avec le rapport mensuel PNLS Côte d'Ivoire.
Les désagrégations par défaut sont **âge × sexe**, avec des tranches
pragmatiques (`<15`, `15-24`, `25-49`, `50+`) reconnues aussi bien
par MER que par le PNLS.

## Sommaire

- [Cascade traitement (TX)](#cascade-traitement-tx)
- [Dépistage (HTS)](#dépistage-hts)
- [PMTCT (Prévention Mère-Enfant)](#pmtct-prévention-mère-enfant)
- [Tuberculose préventive (TB_PREV)](#tuberculose-préventive-tb_prev)
- [Indicateurs de la Vue d'ensemble](#indicateurs-de-la-vue-densemble)

---

## Cascade traitement (TX)

### TX_NEW

**Définition** : nombre de patients qui ont **démarré** un TARV pendant
le trimestre.

**Source** : `core.treatment_initiations.arv_init_date` dans
`[Q.début, Q.fin]`.

**Désagrégation** : âge à la fin du trimestre × sexe (depuis
`core.patients`).

### TX_CURR

**Définition** : nombre de patients **actuellement sous traitement** à
la fin du trimestre.

**Méthode** : un patient est TX_CURR si :

- Il a une initiation `<= Q.fin` (`treatment_initiations`).
- Il n'a pas de clôture `<= Q.fin` (`closures`).
- Sa dernière visite avant `Q.fin` avec un `arv_treatment_days`
  renseigné couvre encore Q.fin (avec une marge de 28 jours de grâce).
- Patient sans aucune visite avec `arv_treatment_days` mais avec
  initiation et sans clôture est gardé (fallback permissif).

**Désagrégation** : âge × sexe.

### TX_PVLS

**Définition** : suppression virale chez les patients sous ARV depuis
au moins 6 mois.

- **Dénominateur** : patients sous ARV `>= 6 mois` à `Q.fin`, sans
  clôture, ayant **au moins un test de charge virale** documenté dans
  les 12 derniers mois.
- **Numérateur** : sous-ensemble dont le dernier test de CV est
  `< 1000 copies/mL`.
- **TX_PVLS (%)** = numérateur / dénominateur.

**Source** : `core.lab_results` avec `test_uuid` = concept charge
virale CIEL 856.

**Désagrégation** : âge × sexe.

---

## Dépistage (HTS)

### HTS_TST

**Définition** : nombre de personnes **dépistées** pendant le trimestre.

**Source** : `core.screenings` avec `screening_date` dans le trimestre.

**Désagrégation** : sexe (depuis `screenings.gender`) × âge (depuis
`screenings.age` — l'âge est stocké au moment du test car le
dépistage est anonyme, pas de lien patient).

### HTS_POS

**Définition** : nombre de tests **positifs** parmi `HTS_TST`.

**Source** : sous-ensemble de `HTS_TST` avec `final_result = 'POS'`.

**Métrique dérivée** : **Positivité** = HTS_POS / HTS_TST.

---

## PMTCT (Prévention Mère-Enfant)

### PMTCT_STAT

**Définition** : femmes enceintes dont le **statut VIH est connu** au
moment de l'admission au CPN (consultation prénatale).

- **Dénominateur** : femmes enceintes ayant démarré un suivi PTME
  dans le trimestre.
- **Numérateur** : sous-ensemble pour lequel `arv_status_at_registering`
  est renseigné OU `spousal_screening_result` est renseigné (proxy
  « statut documenté à l'enregistrement »).

**Source** : `core.ptme_mothers`.

> **Approximation à valider** : ces deux signaux sont une heuristique
> ; la définition exacte sera affinée quand on aura des données PTME
> réelles à analyser.

### PMTCT_ART

**Définition** : femmes enceintes VIH+ qui reçoivent un **TARV**.

- **Dénominateur** : femmes VIH-confirmées dans le trimestre
  (`arv_status_at_registering` renseigné).
- **Numérateur** : sous-ensemble dont `arv_status_at_registering`
  contient « ARV » ou « diagnostiquée » (heuristique sur le label).

> **Approximation à valider** : la heuristique sera remplacée par une
> table de mapping précise une fois les valeurs réelles auditées.

### PMTCT_EID (Early Infant Diagnosis)

**Définition** : enfants exposés au VIH avec un **PCR1 collecté dans
les 2 mois** suivant la naissance.

- **Dénominateur** : enfants nés dans le trimestre (`birth_date` ∈ Q).
- **Numérateur** : sous-ensemble avec `pcr1_sampling_date` non null
  et `pcr1_sampling_date - birth_date <= 60` jours.

**Source** : `core.ptme_children`.

**Désagrégation** : sexe (depuis `gender`) × tranche d'âge — tous les
enfants tombent dans `<15` par construction.

---

## Tuberculose préventive (TB_PREV)

### TB_PREV

**Définition** : patients ayant **terminé** un traitement préventif de
la tuberculose (TPT) pendant le trimestre.

- **Dénominateur** : enregistrements TPT dont `tpt_end_date` tombe
  dans le trimestre.
- **Numérateur** : sous-ensemble avec un `tpt_outcome` non null
  (= une issue documentée = considéré comme « complété »).

**Source** : `core.tpt_records` joint à `core.patients` pour récupérer
sexe + âge.

---

## Indicateurs de la Vue d'ensemble

Indicateurs simplifiés pour le dashboard d'accueil, calculés sur le
**périmètre actif** (région / district / site) :

### File active

Patients ayant **au moins une visite dans les 12 derniers mois**.

Plus permissif que TX_CURR (pas de contrôle de couverture ARV ni de
clôture). Sert d'indicateur de volumétrie générale.

### TX_NEW (mois)

Nombre d'initiations TARV depuis le **début du mois en cours** (et non
du trimestre PEPFAR). Pour avoir le TX_NEW PEPFAR officiel, utilisez
la page **Indicateurs PEPFAR**.

### CV supprimée

Pourcentage de patients ayant une **charge virale < 1000 copies/mL**
sur leurs tests des 12 derniers mois. Équivalent simplifié de TX_PVLS
sans les contrôles d'éligibilité.

### Sites en ligne

Sites dont la **dernière synchronisation est < 24 h**, sur le total
des sites actifs du périmètre.

---

## Bonnes pratiques

- **Comparer trimestre par trimestre** : les indicateurs PEPFAR sont
  conçus pour des comparaisons trimestrielles. Les comparer entre eux
  (TX_NEW vs TX_CURR) n'a de sens qu'à la même borne temporelle.
- **Vérifier le périmètre** : un coordinateur régional voit des
  valeurs régionales ; ne pas les confondre avec les valeurs nationales.
- **Faire confiance au CSV** : pour un audit officiel, l'export CSV
  est plus fiable qu'une copie d'écran (il est horodaté, contient le
  périmètre dans le nom de fichier).
