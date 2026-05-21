# Quickstart — Responsable de site

Ce guide s'adresse aux **utilisateurs site** (rôle `SITE_USER`). Vous
voyez uniquement les données de votre site, et vous vérifiez
régulièrement que la synchronisation tourne.

## 1. Se connecter

Même procédure que pour un coordinateur :

1. Ouvrez l'URL fournie par votre administrateur.
2. Cliquez sur **Se connecter** puis saisissez vos identifiants Keycloak.
3. Vous arrivez sur la **Vue d'ensemble** restreinte à votre site.

## 2. Votre périmètre

En tant que `SITE_USER`, **vous ne voyez que votre site**, et ce
restrictif est appliqué partout : KPIs, listings, CSVs. Le sélecteur
géographique en haut à droite ne vous propose que votre site.

Concrètement :

- Vue d'ensemble : la file active affichée est celle de votre site.
- Indicateurs PEPFAR : TX_NEW, TX_CURR, etc. calculés sur votre site
  uniquement.
- Listings (visites, dispensations, etc.) : uniquement les patients
  de votre site.

## 3. Vérifier que la synchronisation fonctionne

C'est votre principale responsabilité : confirmer que les données
saisies dans OpenMRS arrivent bien au hub.

### 3.a. Sur la Vue d'ensemble

Regardez la zone **Alertes** (en bas à droite) :

- **Dernier batch reçu** doit être récent (idéalement < 1 heure).
- **Sites > 24h sans sync** doit afficher 0 pour votre site.

Si **Dernier batch reçu** indique plusieurs jours ou « Jamais »,
l'agent ne tourne pas. Contactez votre administrateur ou le
responsable technique du site.

### 3.b. Sur la page Synchronisation

Allez dans **Synchronisation** (barre latérale, section Admin).

Vous y voyez la liste des derniers batches reçus pour votre site :

- **Heure** — quand le batch a été traité côté hub.
- **Type** — quel entity type a été poussé (patients, visites, etc.).
- **Reçus / Acceptés / Rejetés** — combien d'enregistrements ont passé
  les validations.

Un site sain présente :

- Plusieurs batches par jour (en général un cycle toutes les 15 min,
  donc ~96 cycles par jour).
- Un ratio Acceptés / Reçus proche de 100 %.
- Pas de batches « FAIL » répétés.

### 3.c. Inspecter les rejets

Onglet **Rejets** sur la même page Synchronisation. Y figurent les
enregistrements qu'OpenMRS a envoyés mais que le hub n'a pas pu
écrire (validation échouée, contrainte unique violée, etc.).

Pour chaque rejet :

- **Code** : le motif technique (`UPSERT_FAILED`, `MISSING_SITE`, etc.).
- **Détail** : le message d'erreur exact.
- **Source UUID** : permet de retrouver le record côté OpenMRS.

Voir [admin/investiguer-rejets.md](admin/investiguer-rejets.md) pour
le workflow complet.

## 4. Consulter les données de votre site

Toutes les pages thématiques (Suivi clinique, Pharmacie, Dépistage,
PTME, TPT, Biologie) sont accessibles et restreintes à votre site.

Cas d'usage typiques :

- **Suivi clinique → Visites** : voir les dernières consultations
  enregistrées et vérifier qu'elles correspondent à ce que vous avez
  saisi sur OpenMRS.
- **Patients → cliquer sur un patient** : voir sa chronologie
  complète (visites, lab, init, clôture).
- **Pharmacie / ARV** : voir les régimes prescrits sur votre site.

## 5. Exporter

Bouton **Exporter CSV** en haut à droite de chaque page. Vous obtenez
un fichier ne contenant que les données de votre site. Utile pour
les contrôles internes ou les rapports mensuels.

## En cas de problème

| Symptôme                                | Première chose à vérifier                                                         |
| --------------------------------------- | --------------------------------------------------------------------------------- |
| Login impossible                        | Mot de passe correct ? Contactez l'administrateur.                                |
| « Dernier batch reçu » trop ancien      | L'agent tourne-t-il sur le poste ? Demandez au responsable IT du site.            |
| Beaucoup de rejets                      | Voir l'onglet Rejets, noter le motif, escalader si récurrent.                     |
| Données saisies mais absentes du hub    | Le cycle agent prend jusqu'à 15 min. Sinon, regarder les rejets.                  |
| Page lente / erreur de chargement       | Recharger la page (F5). Si persistant, signaler à l'administrateur.               |

## Que faire ensuite

- [reference-pages.md](reference-pages.md) — tour complet des pages.
- [glossaire.md](glossaire.md) — termes que vous croiserez (cycle,
  watermark, batch, etc.).
