# Référence — Rôles et périmètres

SIGDEP-3 utilise **8 rôles** Keycloak qui contrôlent à la fois **ce que
vous pouvez voir** (périmètre géographique) et **ce que vous pouvez
faire** (lecture seule, administration, etc.).

## Vue d'ensemble des rôles

| Rôle              | Périmètre par défaut       | Peut écrire ?      | Usage typique                                       |
| ----------------- | -------------------------- | ------------------ | --------------------------------------------------- |
| `SUPER_ADMIN`     | National (tout le pays)    | Oui (tout)         | Équipe technique SIGDEP, opérations critiques       |
| `IT_ADMIN`        | National                   | Oui (admin)        | Administrateur technique : utilisateurs, sync       |
| `NATIONAL_VIEWER` | National                   | Non                | Coordinateur national, PNLS                         |
| `REGIONAL_COORD`  | Une région                 | Non                | Coordinateur régional                               |
| `DISTRICT_COORD`  | Un district                | Non                | Coordinateur de district                            |
| `SITE_USER`       | Un site                    | Non                | Responsable de site, validation locale              |
| `ANALYST`         | National                   | Non                | Analyste de données, statisticien                   |
| `AUDITOR`         | National                   | Non                | Auditeur, contrôle interne                          |

## Comment fonctionne le périmètre

Le périmètre suit une règle simple : **le plus restrictif l'emporte**.
Concrètement :

1. **Votre rôle** définit un plafond — le maximum que vous puissiez voir.
2. **Le sélecteur géographique** dans la console permet de **resserrer**
   davantage (par exemple un coordinateur régional qui zoome sur un
   district précis).
3. **Vous ne pouvez jamais élargir au-delà du plafond de votre rôle.**

Exemples :

- Un `NATIONAL_VIEWER` peut choisir une région dans le sélecteur ;
  il verra alors uniquement cette région.
- Un `REGIONAL_COORD` ne peut pas voir d'autres régions, même si le
  sélecteur affichait techniquement l'option (elle est masquée).
- Un `SITE_USER` n'a qu'un site dans son sélecteur — c'est le sien.

## Détail par rôle

### `SUPER_ADMIN`

- **Voit tout** : tous les sites, toutes les régions, toutes les pages
  admin.
- **Peut tout faire** : créer / désactiver des utilisateurs, modifier
  les rôles, intervenir sur la synchronisation.
- À réserver à 1-2 personnes de confiance dans l'équipe technique.

### `IT_ADMIN`

- Pareil que `SUPER_ADMIN` côté lecture (tout).
- A accès aux pages **Synchronisation**, **Rejets**, **Utilisateurs**.
- Ne peut pas réécrire les données métier (lecture seule sur le clinique).
- Profil pour les administrateurs techniques du PNLS / de l'équipe SIGDEP.

### `NATIONAL_VIEWER`

- Voit tous les indicateurs au niveau national.
- Peut zoomer sur n'importe quelle région / district / site via le
  sélecteur.
- Pas d'accès aux pages admin.
- Profil typique : direction PNLS, partenaires techniques nationaux.

### `REGIONAL_COORD`

- Voit uniquement **sa région** et tout ce qu'elle contient
  (districts, sites).
- Peut zoomer sur un district ou un site de sa région.
- Pas d'accès aux pages admin.
- À attribuer aux coordinations régionales VIH du PNLS.

### `DISTRICT_COORD`

- Voit uniquement **son district** et tout ce qu'il contient.
- Peut zoomer sur un site de son district.
- Pas d'accès aux pages admin.
- À attribuer aux coordinations de district.

### `SITE_USER`

- Voit uniquement **son site**.
- Aucune option de zoom (un seul site dans le sélecteur).
- Pas d'accès aux pages admin.
- À attribuer aux référents informatiques ou aux superviseurs de site.

### `ANALYST`

- Lecture nationale comme un `NATIONAL_VIEWER`.
- Accès supplémentaire aux **timelines patient détaillées** et aux
  exports CSV non agrégés.
- Profil pour les analystes / chercheurs autorisés.

### `AUDITOR`

- Lecture nationale.
- Accès aux **logs d'audit** (table `audit.sync_batch` côté hub).
- Pas d'écriture, pas d'admin utilisateurs.
- Profil pour les auditeurs internes ou externes.

## Comment un rôle est-il attribué ?

Côté Keycloak :

1. L'administrateur (`SUPER_ADMIN` ou `IT_ADMIN`) ouvre la page
   **Utilisateurs** de la console.
2. Il crée ou ouvre un utilisateur.
3. Il sélectionne le **rôle** et le **périmètre géographique**
   (région / district / site selon le rôle).
4. Le périmètre est encodé dans le JWT à la prochaine connexion de
   l'utilisateur.

Voir [admin/gestion-utilisateurs.md](admin/gestion-utilisateurs.md) pour
la procédure complète.

## Cumul de rôles

Un utilisateur peut techniquement avoir plusieurs rôles (par exemple
`NATIONAL_VIEWER` + `AUDITOR`). En pratique, n'attribuez qu'un seul
rôle métier par utilisateur — le cumul rend les permissions difficiles
à raisonner.

L'exception qui se justifie : `IT_ADMIN` cumulé avec `NATIONAL_VIEWER`
pour qu'un administrateur technique puisse aussi consulter les
dashboards nationaux.
