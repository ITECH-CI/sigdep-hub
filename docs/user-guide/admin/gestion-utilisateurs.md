# Gestion des utilisateurs

Cette page est destinée aux **administrateurs** (`SUPER_ADMIN` ou
`IT_ADMIN`). Elle décrit comment créer, modifier, désactiver les
comptes Keycloak et leur attribuer un rôle + un périmètre.

## Accéder à la page

Dans la barre latérale, section **Admin**, cliquer sur
**Utilisateurs**. Si l'option n'apparaît pas, vous n'avez pas le
rôle requis.

## Vue d'ensemble

La page liste tous les utilisateurs du realm `sigdep`. Pour chacun :

- Identifiant (username).
- Prénom / nom.
- Email.
- Statut (actif / désactivé).
- Rôles attribués.
- Périmètre géographique (région / district / site selon le rôle).

Une barre de **recherche** filtre la liste par nom ou email.

## Créer un utilisateur

1. Cliquer sur **Nouvel utilisateur**.
2. Renseigner les champs obligatoires :
   - **Identifiant** : nom d'utilisateur unique (sans accent, sans
     espace ; par exemple `coord_san_pedro`).
   - **Prénom / Nom**.
   - **Email** (utilisé pour la récupération de mot de passe).
   - **Mot de passe initial** + **confirmation**. À transmettre à
     l'utilisateur de manière sécurisée ; il devra le changer à sa
     première connexion.
3. Choisir le **rôle** (voir [reference-roles.md](../reference-roles.md)).
4. Selon le rôle, sélectionner le **périmètre géographique** :
   - `SITE_USER` : un site précis (cascade Région → District → Site).
   - `DISTRICT_COORD` : un district (cascade Région → District).
   - `REGIONAL_COORD` : une région.
   - Les autres rôles n'ont pas de périmètre (= national).
5. Cliquer sur **Créer**.

L'utilisateur peut immédiatement se connecter avec son identifiant et
le mot de passe initial.

## Modifier un utilisateur

Cliquer sur la ligne pour ouvrir le panneau de détail :

- **Prénom / Nom / Email** : modifiables directement.
- **Rôles** : ajouter ou retirer via les cases à cocher.
- **Périmètre** : modifier les sélecteurs Région / District / Site.
- **Mot de passe** : bouton dédié « Réinitialiser le mot de passe ».
- **Désactiver** : un compte désactivé ne peut plus se connecter
  mais reste dans le système (historique préservé).

Cliquer sur **Enregistrer** pour valider.

## Cas pratiques

### Un coordinateur change de région

1. Ouvrir l'utilisateur.
2. Modifier le sélecteur **Région** vers la nouvelle affectation.
3. Enregistrer.

L'utilisateur verra ses nouveaux périmètres à sa prochaine connexion
(ou à la prochaine rotation de son JWT, max 15 min).

### Un site est confié à une nouvelle équipe

Plutôt que de modifier le compte existant, créer un nouvel utilisateur
pour la nouvelle équipe et **désactiver** l'ancien. Garde la
traçabilité.

### Un compte semble compromis

1. Ouvrir l'utilisateur.
2. **Désactiver** immédiatement.
3. **Réinitialiser le mot de passe** (le nouveau ne sera utilisable
   qu'après réactivation).
4. Investiguer via les logs Keycloak (admin console direct sur le
   port 8180 en développement).

## Bonnes pratiques

- **Un utilisateur = une personne physique.** Ne partagez pas de
  comptes entre plusieurs personnes.
- **Mot de passe initial fort** (au moins 12 caractères mixtes).
  Forcer le changement à la première connexion via Keycloak.
- **Désactiver, ne pas supprimer.** La suppression d'un compte fait
  perdre la traçabilité dans les logs.
- **Auditer périodiquement** la liste des comptes actifs, surtout
  pour les `IT_ADMIN` et `SUPER_ADMIN`.
- **Email obligatoire** sur tout compte qui doit pouvoir récupérer
  son mot de passe en autonomie.

## Limites actuelles

- La double authentification (2FA) n'est pas activée par défaut sur
  le realm SIGDEP. À envisager pour les rôles `SUPER_ADMIN` et
  `IT_ADMIN` en production.
- L'import en masse d'utilisateurs (CSV / API) passe par l'admin
  Keycloak direct (`/admin` sur le port 8180) plutôt que par la page
  Utilisateurs.

## Voir aussi

- [reference-roles.md](../reference-roles.md) — qui voit quoi.
- [investiguer-rejets.md](investiguer-rejets.md) — workflow Rejets.
