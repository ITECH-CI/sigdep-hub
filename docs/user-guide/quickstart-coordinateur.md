# Quickstart — Coordinateur

Ce guide s'adresse aux **coordinateurs nationaux, régionaux ou de
district** (rôles `NATIONAL_VIEWER`, `REGIONAL_COORD`, `DISTRICT_COORD`).
Vous consultez les données, vous n'en saisissez pas.

## 1. Se connecter

1. Ouvrez votre navigateur sur l'URL fournie par l'équipe SIGDEP
   (par exemple `https://sigdep.pnls.ci/` en production).
2. Cliquez sur **Se connecter**. Vous êtes redirigé vers la page de
   login Keycloak SIGDEP.
3. Saisissez votre identifiant (email ou nom d'utilisateur) et votre
   mot de passe, puis validez.
4. Si c'est votre première connexion, Keycloak peut vous demander de
   changer votre mot de passe. Faites-le immédiatement.

À la fin, vous arrivez sur la **Vue d'ensemble** (Dashboard).

> **En cas de problème** : mot de passe oublié → utilisez le lien
> « Mot de passe oublié ? » sur la page de login (vous recevrez un
> email de réinitialisation si votre compte a une adresse renseignée).
> Sinon, contactez l'administrateur.

## 2. Comprendre votre périmètre

La console restreint automatiquement ce que vous voyez selon votre rôle :

- **NATIONAL_VIEWER** : tout le pays.
- **REGIONAL_COORD** : tous les sites de votre région.
- **DISTRICT_COORD** : tous les sites de votre district.

Le périmètre est appliqué partout — KPIs, listes, exports. Vous ne
pouvez pas voir au-delà.

Vous pouvez **resserrer** ce périmètre via le sélecteur géographique
en haut à droite de chaque page (Région → District → Site).

## 3. Lire la Vue d'ensemble

C'est votre point d'entrée. Au premier coup d'œil, vous voyez :

- **File active** — patients ayant eu au moins une visite dans les
  12 derniers mois sur votre périmètre.
- **TX_NEW (mois)** — patients nouvellement initiés au TARV dans le
  mois en cours.
- **CV supprimée** — pourcentage de patients avec une charge virale
  < 1000 copies/mL sur les 12 derniers mois.
- **Sites en ligne** — sites synchronisés dans les 24 dernières heures
  sur le nombre total de sites actifs de votre périmètre.

Sous les KPIs, vous trouvez :

- Un graphe **File active sur 12 mois glissants**.
- Une zone d'**alertes** (sites sans sync depuis > 7 jours / 24 h,
  dernier batch reçu).
- Une **répartition de la file active par région** (visible
  uniquement si votre périmètre couvre plusieurs régions).

## 4. Consulter les indicateurs PEPFAR

Cliquez sur **Indicateurs PEPFAR** dans la barre latérale.

1. Choisissez l'**année fiscale** (FY) et le **trimestre** (Q1 à Q4)
   en haut à droite.
2. Lisez les KPIs : TX_NEW, TX_CURR, TX_PVLS, HTS_TST, HTS_POS,
   PMTCT_ART, TB_PREV.
3. Faites défiler pour voir les **tableaux désagrégés** par tranche
   d'âge et par sexe pour chaque indicateur.

Voir [reference-indicateurs.md](reference-indicateurs.md) pour les
définitions précises.

## 5. Exporter un rapport en CSV

Chaque page de données dispose d'un bouton **Exporter CSV** en haut
à droite. Le CSV exporté reflète :

- Le **périmètre actif** (région / district / site sélectionnés).
- Le **filtre temporel** courant (trimestre PEPFAR, période en mois,
  etc.).

Le fichier s'ouvre directement dans Excel (encodage UTF-8 avec BOM,
séparateur point-virgule, dates au format ISO `YYYY-MM-DD`).

Exemples :

- Export PEPFAR : `pepfar-FY2025Q3.csv` — un indicateur par ligne avec
  sexe / tranche d'âge / valeur.
- Export Suivi clinique : `clinique-12m.csv` — une visite par ligne.

## 6. Naviguer dans les thématiques

Dans la barre latérale, sous « Données » :

- **Suivi clinique** — visites, initiations, clôtures, IVSA (onglets).
- **Pharmacie / ARV** — dispensations par régime.
- **Dépistage** — clients testés, positivité par porte d'entrée.
- **PTME** — mères enceintes suivies, enfants exposés (onglets).
- **TPT** — traitement préventif de la tuberculose.
- **Biologie** — examens (CD4, charge virale).

Chaque page suit la même logique : KPIs en haut, distributions au
milieu, listing détaillé avec tri et CSV en bas.

## Que faire ensuite

- Lire [reference-pages.md](reference-pages.md) pour un tour complet
  de la console.
- Si un site de votre périmètre semble en panne (rouge dans la zone
  d'alertes), contactez l'administrateur.
- Pour comprendre un indicateur précis :
  [reference-indicateurs.md](reference-indicateurs.md).
