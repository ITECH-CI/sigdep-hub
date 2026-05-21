# Guide utilisateur SIGDEP-3

Bienvenue dans la documentation utilisateur du **Serveur consolidé de
données de suivi des patients vivant avec le VIH en Côte d'Ivoire**
(SIGDEP-3).

Cette documentation est volontairement courte. Chaque fichier tient en
quelques minutes de lecture et couvre un usage précis. Si vous cherchez
un sujet qui n'apparaît pas ici, signalez-le à l'équipe SIGDEP : la
doc évolue avec les retours du terrain.

## Qui lit quoi

Choisissez l'entrée qui correspond à votre rôle :

| Vous êtes…                                | Commencez par                                                              |
| ----------------------------------------- | -------------------------------------------------------------------------- |
| Coordinateur national / régional / district | [Quickstart coordinateur](quickstart-coordinateur.md)                       |
| Responsable de site (SITE_USER)           | [Quickstart site](quickstart-site-user.md)                                  |
| Administrateur (IT_ADMIN / SUPER_ADMIN)   | [Gestion des utilisateurs](admin/gestion-utilisateurs.md) puis [Investiguer les rejets](admin/investiguer-rejets.md) |
| Déployeur / installateur                  | [Installer le hub](deploiement/installer-hub.md) puis [Installer un agent](deploiement/installer-agent.md) ; pour un pilote, partir de la [checklist de déploiement](deploiement/pilote-checklist.md) |

## Documents de référence

À consulter dès que vous avez besoin de plus de détails :

- [Référence des pages de la console](reference-pages.md) — tour
  rapide de chaque écran : Dashboard, PEPFAR, Suivi clinique, etc.
- [Référence des indicateurs](reference-indicateurs.md) — définitions
  de TX_NEW, TX_CURR, TX_PVLS, HTS, PMTCT, TB_PREV.
- [Référence des rôles et périmètres](reference-roles.md) — qui voit
  quoi : du SUPER_ADMIN au SITE_USER.
- [Glossaire](glossaire.md) — termes métier (file active, IIT, MSD…)
  et techniques (DEAD_LETTER, watermark, scope…).

## Comprendre la plateforme en deux phrases

SIGDEP-3 collecte les données de prise en charge VIH depuis les sites
(OpenMRS local) via un **agent de synchronisation**, et les agrège
dans un **hub central** consultable par une console web.

La console offre des indicateurs PEPFAR (TX_NEW, TX_CURR, TX_PVLS, HTS,
PMTCT, TB_PREV), des tableaux thématiques (suivi clinique, pharmacie,
dépistage, PTME, TPT, biologie), une page patient, et des écrans
d'administration (synchronisation, rejets, utilisateurs).

## Conventions

Dans toute la doc :

- **« la console »** = l'interface web, accessible par défaut sur
  `http://localhost:9000/` en développement, ou sur l'URL fournie par
  votre administrateur en production.
- **« le hub »** = le serveur central (côté minisère/PNLS).
- **« un site »** = un établissement de soins (CHU, CHR, CSU, etc.)
  qui exécute OpenMRS et l'agent de synchronisation.
- **« un cycle agent »** = un round de synchronisation (toutes les
  15 minutes par défaut) où l'agent extrait les nouveaux records et
  les pousse au hub.
