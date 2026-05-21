# Glossaire

Termes métier et techniques que vous croiserez dans la console
SIGDEP-3 et dans la documentation.

## Termes métier (VIH / PEPFAR)

**ARV** — AntiRétroViraux. Médicaments qui contrôlent l'infection
par le VIH.

**Cascade 95-95-95** — objectif UNAIDS : 95 % des personnes infectées
connaissent leur statut, 95 % d'entre elles sont sous ARV, 95 % de
celles-ci ont une charge virale supprimée.

**CDC stage** — stade clinique CDC (A, B, C). Alternative à la
classification OMS, utilisée historiquement aux US.

**CMU** — Couverture Maladie Universelle. Identifiant national CI.

**CODE ARV** — identifiant national d'un patient sous ARV en Côte
d'Ivoire.

**CV (charge virale)** — quantité de virus dans le sang du patient,
en copies/mL. Indicateur d'efficacité du traitement.

**File active** — patients actuellement suivis par le programme, en
général définis comme ayant eu une visite dans les 12 derniers mois.

**HSH** — Hommes ayant des rapports Sexuels avec des Hommes.
Population-clé du programme VIH.

**HTS** — HIV Testing Services. Indicateur PEPFAR du dépistage.

**IIT (Interruption In Treatment)** — patient qui rate ses rendez-vous
au-delà d'un seuil (28 jours typiquement). Précurseur de la perte
de vue.

**Initiation** — entrée d'un patient dans le programme ARV (= mise
sous traitement).

**IVSA** — Initiative pour la Visite Sans rendez-vous Améliorée.
Sous-module de la fiche de suivi pour les patients « non stables »
(MSD = 2).

**MMD** — Multi-Month Dispensing. Dispensation de plusieurs mois
d'ARV en une fois (3 mois, 6 mois).

**MSD** — Modèle de Soins Différenciés. Catégorisation du parcours :
Standard / IVSA / Échec thérapeutique.

**OMS stage** — stade clinique OMS (I, II, III, IV). Sévérité de
l'infection au moment du diagnostic.

**Perdu de vue (PDV)** — patient qui n'a plus de visite documentée
au-delà d'un seuil (souvent 90 jours après le RDV manqué).

**PEC** — Prise En Charge. Préfixe utilisé sur les fiches OpenMRS
(PEC - Fiche Initiale, PEC - Suivi patient, PEC - Suivi TPT, etc.).

**PEPFAR** — President's Emergency Plan for AIDS Relief.
Programme américain finançant la lutte contre le VIH dans le monde.

**PNLS** — Programme National de Lutte contre le SIDA. Autorité
nationale CI.

**PTME** — Prévention de la Transmission Mère-Enfant. Module
spécifique de prise en charge des femmes enceintes / enfants
exposés.

**TARV** — Traitement AntiRétroViral. Synonyme d'ARV utilisé dans la
documentation francophone.

**TPT** — Traitement Préventif de la Tuberculose. Donné aux personnes
VIH+ pour réduire le risque de TB active.

**TX_NEW / TX_CURR / TX_PVLS** — indicateurs PEPFAR de la cascade
traitement. Voir [reference-indicateurs.md](reference-indicateurs.md).

**UPID** — Unique Patient Identifier. Identifiant national CI du
patient.

## Termes techniques (architecture SIGDEP)

**Agent (sigdep-sync)** — programme Java déployé sur le poste d'un
site. Lit OpenMRS local, transforme en DTOs, et pousse au hub
toutes les 15 min.

**Batch** — lot d'enregistrements envoyés du même type en une
requête HTTP (par défaut 500 records max).

**Concept (OpenMRS)** — élément du dictionnaire de données OpenMRS,
référencé par UUID ou ID numérique. Exemple : poids (5089), CV
(856), MSD (165063).

**Console (sigdep-console)** — interface web React, accessible aux
utilisateurs métier.

**Cycle** — un round complet de synchronisation : extraction +
enqueue + push + ajustement watermark. Lancé toutes les 15 min par
défaut.

**DEAD_LETTER** — statut d'un rejet qui a échoué `max-reject-attempts`
fois (10 par défaut). L'agent ne le rejoue plus automatiquement ;
intervention humaine requise.

**DTO** — Data Transfer Object. Format pivot des données entre
l'agent, le hub et la console (`PatientDto`, `VisitDto`, etc.).

**Encounter (OpenMRS)** — événement clinique (consultation, examen,
admission). Porte des `obs` (observations).

**Hub (sigdep-hub)** — serveur central : PostgreSQL + ingestion-api

- console-api + Keycloak.

**JWT** — JSON Web Token. Jeton signé délivré par Keycloak après
authentification. Contient le rôle et le périmètre de l'utilisateur.

**Keycloak** — serveur d'authentification (Single Sign-On). Gère les
utilisateurs et les jetons d'accès du hub.

**Liquibase** — outil de migration de schéma SQL. Les migrations
sont versionnées dans
`ingestion-api/src/main/resources/db/changelog/`.

**Obs (OpenMRS)** — une observation, c.-à-d. un point de donnée
(valeur + concept + encounter). La fiche de suivi capture des
dizaines d'obs par visite.

**OIDC / OAuth2** — protocoles d'authentification utilisés entre
Keycloak, la console et les agents.

**Outbox** — file SQLite locale de l'agent qui garde les records à
envoyer. Permet de fonctionner hors-ligne et de rejouer les rejets.

**Realm (Keycloak)** — espace logique d'authentification. SIGDEP-3
utilise un seul realm appelé `sigdep`.

**Source UUID** — identifiant OpenMRS d'un enregistrement (patient,
visite, etc.). Conservé dans le hub pour permettre l'upsert
idempotent.

**Scope (AuthScope)** — périmètre effectif du JWT. Combinaison du
rôle Keycloak et d'attributs (`regionId`, `districtId`, `siteId`).
Appliqué partout dans la console.

**Upsert** — INSERT ou UPDATE selon que la clé existe déjà.
Pattern utilisé par le hub pour ré-ingérer les mêmes records
sans créer de doublons.

**Watermark** — date du dernier record extrait par l'agent pour
une entité donnée (un par entité). Stocké dans
`sync_state` (SQLite local). Avance après chaque cycle réussi.
Au reset, repart de `sigdep.sync.watermark-initial`
(souvent `1970-01-01T00:00:00`).

## Pages et écrans

**Dashboard / Vue d'ensemble** — page d'entrée de la console.

**Pepfar** — page des indicateurs PEPFAR trimestriels.

**Suivi clinique** — page éclatée en 4 onglets (Initiations, Visites,
IVSA, Clôtures).

**Synchronisation** — page admin (Batches reçus + Rejets).

**Utilisateurs** — page admin pour gérer les comptes Keycloak.

## Acronymes courts

- **ANC** — AnteNatal Care (consultation prénatale)
- **API** — Application Programming Interface
- **CSV** — Comma-Separated Values (en SIGDEP : séparé par `;`)
- **EID** — Early Infant Diagnosis (PCR1 ≤ 2 mois)
- **FY** — Fiscal Year (année fiscale, oct→sep pour PEPFAR)
- **JSON** — JavaScript Object Notation
- **MER** — Monitoring, Evaluation and Reporting (référentiel PEPFAR)
- **PCR** — Polymerase Chain Reaction (test virologique)
- **REST** — Representational State Transfer (style d'API HTTP)
- **SQL** — Structured Query Language
- **SSO** — Single Sign-On
- **TLS** — Transport Layer Security (HTTPS)
- **UUID** — Universally Unique Identifier
