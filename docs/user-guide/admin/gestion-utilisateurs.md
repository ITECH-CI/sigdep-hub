# Gestion des utilisateurs

Cette page est destinée aux **administrateurs** (`SUPER_ADMIN` ou
`IT_ADMIN`). Elle décrit comment créer, modifier, désactiver les
comptes utilisateurs et leur attribuer un rôle + un périmètre.

> Depuis la v2.0, l'authentification est gérée par le hub lui-même
> (Spring Security + JWT) : le login se fait par **adresse e-mail** et
> chaque compte porte **un seul rôle**.

## Accéder à la page

Dans la barre latérale, section **Admin**, cliquer sur
**Utilisateurs**. Si l'option n'apparaît pas, vous n'avez pas le
rôle requis.

## Vue d'ensemble

La page liste tous les comptes. Pour chacun :

- Email (= identifiant de connexion).
- Nom complet.
- Rôle.
- Périmètre géographique (région / district / site selon le rôle).
- Statut (actif / désactivé).
- Dernière connexion.

Une barre de **recherche** filtre la liste par nom ou email.

## Créer un utilisateur

1. Cliquer sur **Nouvel utilisateur**.
2. Renseigner les champs obligatoires :
   - **Adresse e-mail** : unique, sert d'identifiant de connexion.
   - **Nom complet**.
   - **Mot de passe initial** + **confirmation**. À transmettre à
     l'utilisateur de manière sécurisée ; cocher « temporaire » pour
     forcer le changement à la première connexion.
3. Choisir le **rôle** (voir [reference-roles.md](../reference-roles.md)).
4. Selon le rôle, sélectionner le **périmètre géographique** :
   - `SITE_USER` : un site précis (cascade Région → District → Site).
   - `DISTRICT_COORD` : un district (cascade Région → District).
   - `REGIONAL_COORD` : une région.
   - Les autres rôles n'ont pas de périmètre (= national).
5. Cliquer sur **Créer**.

L'utilisateur peut immédiatement se connecter avec son e-mail et le
mot de passe initial.

## Modifier un utilisateur

Cliquer sur **Éditer** pour ouvrir le panneau de détail :

- **Nom complet** : modifiable directement.
- **Rôle** : un seul rôle par compte (sélecteur). Changer de rôle
  réinitialise le périmètre.
- **Périmètre** : sélecteurs Région / District / Site (selon le rôle).
- **Compte actif** : décocher pour désactiver (le compte reste en
  base, historique préservé).
- **Mot de passe** : bouton dédié « Mot de passe » (réinitialisation).
  Un reset révoque les sessions en cours de l'utilisateur.

Cliquer sur **Enregistrer** pour valider.

## Cas pratiques

### Un coordinateur change de région

1. Ouvrir l'utilisateur.
2. Modifier le sélecteur **Région** vers la nouvelle affectation.
3. Enregistrer.

L'utilisateur verra ses nouveaux périmètres à sa prochaine connexion
(ou au prochain rafraîchissement de son token, l'access token expirant
au bout d'une heure).

### Un site est confié à une nouvelle équipe

Plutôt que de modifier le compte existant, créer un nouvel utilisateur
pour la nouvelle équipe et **désactiver** l'ancien. Garde la
traçabilité.

### Un compte semble compromis

1. Ouvrir l'utilisateur.
2. **Désactiver** immédiatement.
3. **Réinitialiser le mot de passe** (le reset révoque aussi les
   refresh tokens en cours ; le nouveau mot de passe ne sera utilisable
   qu'après réactivation du compte).
4. Investiguer via les logs `console-api` (`docker compose logs
   console-api`).

## Bonnes pratiques

- **Un utilisateur = une personne physique.** Ne partagez pas de
  comptes entre plusieurs personnes.
- **Mot de passe initial fort** (au moins 12 caractères mixtes).
  Cocher « temporaire » pour forcer le changement à la première
  connexion.
- **Désactiver, ne pas supprimer.** La suppression d'un compte fait
  perdre la traçabilité dans les logs.
- **Auditer périodiquement** la liste des comptes actifs, surtout
  pour les `IT_ADMIN` et `SUPER_ADMIN`.
- **Email obligatoire** sur tout compte qui doit pouvoir récupérer
  son mot de passe en autonomie.

## Limites actuelles

- La double authentification (2FA) n'est pas encore disponible. À
  envisager pour les rôles `SUPER_ADMIN` et `IT_ADMIN` en production.
- L'import en masse d'utilisateurs (CSV / API) n'est pas exposé dans
  l'interface ; les comptes se créent un par un via la page
  Utilisateurs.
- Un compte porte **un seul rôle**. Pour des droits cumulés, choisir
  le rôle le plus large adapté (cf. [reference-roles.md](../reference-roles.md)).

## Voir aussi

- [reference-roles.md](../reference-roles.md) — qui voit quoi.
- [investiguer-rejets.md](investiguer-rejets.md) — workflow Rejets.
