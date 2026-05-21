# Investiguer les rejets

Cette page est destinée aux **administrateurs** (`SUPER_ADMIN`,
`IT_ADMIN`) et aux **responsables de site** (`SITE_USER`) qui veulent
comprendre pourquoi certains enregistrements envoyés par l'agent ne
sont pas écrits dans le hub.

## Qu'est-ce qu'un rejet ?

Lorsque l'agent pousse un batch (par exemple 50 visites), le hub
valide chaque enregistrement avant insertion. Si un enregistrement
échoue la validation (contrainte SQL, donnée invalide, site
inconnu…), il est **rejeté** :

- L'agent reçoit la liste des rejets dans la réponse HTTP.
- Le hub écrit chaque rejet dans `audit.rejected_record` avec un
  code et un message.
- L'enregistrement n'est pas perdu : il reste dans l'outbox de
  l'agent et sera rejoué au prochain cycle.
- Après **10 tentatives** infructueuses, le rejet passe en
  `DEAD_LETTER` : il ne sera plus rejoué automatiquement et nécessite
  une action manuelle (l'envoi via cette voie est gelé).

## Accéder à la page Rejets

Dans la barre latérale, section **Admin**, cliquer sur
**Synchronisation** puis ouvrir l'onglet **Rejets**.

La liste affiche :

- **Heure** — quand le rejet a été enregistré.
- **Site** — code du site source.
- **Entité** — type d'enregistrement (`patients`, `visits`,
  `treatment_initiations`, etc.).
- **Code** — motif technique (voir tableau plus bas).
- **Statut** — `OPEN` (en attente) ou `RESOLVED` (marqué résolu par
  un admin).

Cliquer sur une ligne ouvre le détail :

- Source UUID (= identifiant OpenMRS, utile pour retrouver le record).
- Message d'erreur complet du hub.
- Compteur de tentatives.
- Heure de résolution + identifiant du résolveur (si résolu).
- Bouton **Marquer comme résolu** (admin uniquement).

## Codes d'erreur courants

| Code              | Cause typique                                                      | Action                                                              |
| ----------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------- |
| `UPSERT_FAILED`   | Erreur SQL lors de l'INSERT/UPDATE (contrainte, type, NOT NULL…)   | Lire le message, identifier la colonne en cause, corriger la source |
| `MISSING_SITE`    | Le `siteCode` envoyé par l'agent n'existe pas dans `core.sites`    | Ajouter le site dans le référentiel ou corriger la config agent     |
| `PATIENT_UNKNOWN` | La visite/init référence un patient absent de `core.patients`      | Le patient sera envoyé au prochain cycle, attendre 1-2 cycles       |
| `INVALID_PAYLOAD` | JSON malformé / champ requis manquant                              | Bug agent à signaler à l'équipe technique                           |
| `NUMERIC_OVERFLOW`| Valeur numérique trop grande pour la colonne                       | Vérifier l'unité côté source (grammes vs kg, par exemple)           |

## Workflow d'investigation

1. **Repérer les codes les plus fréquents** dans l'onglet Rejets.
   Trier par code et compter. Un site avec 500 `MISSING_SITE` est
   un site mal configuré ; 2 ou 3 `UPSERT_FAILED` isolés sont
   probablement des données corrompues à la source.

2. **Lire le message complet** d'un rejet représentatif. Le message
   pointe en général la colonne et la valeur en cause.

3. **Identifier la cause** :
   - Côté **source OpenMRS** : valeur aberrante, champ manquant,
     UUID dupliqué…
   - Côté **agent** : version trop ancienne, mauvaise config, concept
     mappé incorrectement.
   - Côté **hub** : schéma incohérent (rare — signaler l'équipe).

4. **Corriger à la source** : c'est la règle. Modifier OpenMRS,
   relancer l'agent, vérifier que le rejet ne réapparaît pas.

5. **Marquer comme résolu** une fois le problème confirmé corrigé.
   Cela retire le rejet de la liste OPEN ; l'historique reste
   disponible via les filtres.

## Cas particuliers

### Rejet récurrent qui ne se résout pas

Si le même enregistrement est rejeté à chaque cycle (compteur
`attempts` qui monte) :

- **Avant 10 tentatives** : le système réessaye, normal.
- **À 10 tentatives** : passe en `DEAD_LETTER`. L'agent arrête de
  rejouer. **Marquer comme résolu** après correction à la source,
  puis demander à l'utilisateur de modifier l'enregistrement
  OpenMRS (toute modification réinitialise le compteur).

### Rejets après changement de schéma

Si une migration côté hub a changé un type ou ajouté une contrainte,
les batches en attente peuvent être rejetés en masse. Le bon réflexe :

1. Vérifier que la migration est appliquée (`databasechangelog`).
2. Marquer en masse les rejets pré-migration comme résolus si la
   donnée a été corrigée à la source.

### Un site ne synchronise plus

Si un site n'envoie plus de batches **ni** de rejets, le problème
n'est pas un rejet — c'est l'agent qui ne tourne pas. Voir la zone
Alertes de la Vue d'ensemble (« Sites > 24 h sans sync »).

## Bonnes pratiques

- **Réviser les rejets toutes les semaines** au minimum, surtout
  pendant les premières semaines après le déploiement d'un nouveau
  site.
- **Communiquer avec le terrain** : un rejet vient en général d'un
  geste de saisie. Former les opérateurs réduit le volume de rejets.
- **Ne pas marquer comme résolu sans vérification** : la résolution
  signale que la cause est traitée. Un rejet résolu trop vite sans
  correction sera ré-émis au cycle suivant.

## Voir aussi

- [quickstart-site-user.md](../quickstart-site-user.md) — section 3.c
  pour le point de vue site.
- [glossaire.md](../glossaire.md) — DEAD_LETTER, watermark, outbox.
- `sigdep-hub/infra/scripts/README.md` — script `reset-hub.sh` au cas
  où un reset complet serait nécessaire.
