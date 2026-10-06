# Politique de sécurité

GE-Televerse-Back traite des documents téléversés par des usagers dans le cadre de
démarches administratives de l'État de Genève. Les rapports de vulnérabilité sont
pris au sérieux et traités en priorité.

## Versions supportées

Seule la dernière version publiée de la ligne `1.0.x` reçoit des correctifs de
sécurité. Les versions `0.x` étaient des pré-versions destinées aux environnements
de test et ne sont plus maintenues.

| Version | Supportée |
|---|---|
| 1.0.x   | ✅ |
| < 1.0   | ❌ |

Il n'y a pas de *backport* vers une version antérieure : un correctif de sécurité
est livré dans la ligne de release en cours, et la mise à jour vers la dernière
version publiée est la voie de remédiation attendue.

## Signaler une vulnérabilité

**N'ouvrez pas d'issue publique et ne décrivez pas la faille dans une pull request.**
Une vulnérabilité rendue publique avant correction expose l'application en production.

Écrivez à l'adresse suivante : [opensource@etat.ge.ch](mailto:opensource@etat.ge.ch).

Merci d'inclure, dans la mesure du possible :

- une description de la faille et de son impact estimé ;
- les étapes de reproduction (requêtes HTTP, fichiers d'exemple, version concernée) ;
- la configuration utilisée si elle diffère de celle du dépôt ;
- toute proposition de correctif ou de contournement.

**Délais.** Un accusé de réception est envoyé sous 5 jours ouvrés. Une première
évaluation (criticité, périmètre, calendrier envisagé) suit sous 15 jours ouvrés.

**Divulgation coordonnée.** Nous vous demandons de laisser un délai raisonnable pour
le déploiement du correctif avant toute publication. Sauf demande contraire de votre
part, votre contribution sera créditée dans les notes de version.

## Traitement des correctifs de vulnérabilité

### Qualification

Chaque signalement est qualifié selon son impact réel sur le service déployé, pas
selon le score brut d'un outil. Une CVE affectant une dépendance présente mais dont
le code vulnérable n'est jamais atteint est traitée en montée de version de routine ;
une faille exploitable sans authentification sur le chemin de téléversement est
traitée en urgence.

Sont considérés comme critiques, sans préjuger des autres cas : l'exécution de code
via un document téléversé, la lecture ou la récupération du document d'un autre
usager, le contournement du scan antivirus, et le contournement de la validation JWT
sur `GET /api/documents/{documentId}`.

### Livraison

- Le correctif est développé dans la ligne de release en cours et livré en version
  **patch** (`1.0.x`), sauf si la correction impose une rupture de contrat.
- Il est consigné dans le [CHANGELOG](CHANGELOG.md), sous la rubrique `Sécurité` de
  la version concernée, avec la référence CVE lorsqu'elle existe.
- Le message de commit porte le préfixe `fix(sécurité):`, ce qui rend la mesure
  traçable dans l'historique.

Deux exemples issus de l'historique du projet : la montée de Netty 4.2.14 → 4.2.15
pour la CVE-2026-42587 (`netty-codec-http`), livrée en `0.2.15` ; et le durcissement
pré-pentest de `0.2.14` (bornes anti-DoS sur le traitement image et PDF, `-dSAFER`
sur les invocations Ghostscript, plafond multipart).

### Dépendances

Les vulnérabilités des dépendances sont détectées par **Sonatype Nexus IQ**, sur les
systèmes internes de l'État (cf. [SCA](#analyse-de-sécurité--sast-sca-sbom)). Ses
constats sont qualifiés selon les mêmes critères que ci-dessus : c'est l'atteignabilité
du code vulnérable qui décide de l'urgence, pas le score seul.

Les montées de version de sécurité sont appliquées à la dépendance concernée, et de
préférence via une propriété de version du BOM lorsque la famille de bibliothèques le
permet — la version de Netty est ainsi pilotée par une propriété unique consommée par
le BOM Spring Boot pour tous les artefacts `io.netty:*` d'un coup, ce qui évite de
laisser un artefact en arrière lors d'un correctif de CVE.

## Analyse de sécurité : SAST, SCA, SBOM

Les analyses sont exécutées par la chaîne d'intégration interne de l'État, à partir
d'un pipeline partagé entre projets. Ni ce pipeline ni sa configuration ne vivent
dans ce dépôt : ils décrivent l'infrastructure et sont donc restés du côté non
publié (cf. [Où vit la chaîne de build](CONTRIBUTING.md#où-vit-la-chaîne-de-build)).
Ce dépôt ne déclare par ailleurs **aucun plugin de scan dans son propre POM** : les
scans ne se déclenchent pas sur un `mvn verify` local.

| Type | Outil | Portée | Configuration |
|---|---|---|---|
| **SAST** — analyse statique du code et de la qualité | SonarQube | sources et tests Java | chemins de binaires déclarés côté chaîne d'intégration ; coordonnées du serveur et jeton fournis par le pipeline |
| **IaC / conteneur** — analyse de configuration | KICS | Dockerfile de production, manifestes de déploiement | exclusions annotées en clair à l'endroit concerné (`# kics-scan ignore-line`), la règle visée étant citée en commentaire |
| **SCA** — vulnérabilités et licences des dépendances | Sonatype Nexus IQ | dépendances Maven, transitives comprises | analyse pilotée par le pipeline partagé, sur les systèmes internes de l'État |
| **SBOM** — nomenclature logicielle | Sonatype Nexus IQ | image applicative et ses dépendances | clé de publication déclarée côté chaîne d'intégration |

SCA et SBOM proviennent donc de la même analyse Nexus IQ : la nomenclature publiée
est celle sur laquelle les vulnérabilités ont été évaluées, ce qui évite l'écart
classique entre l'inventaire déclaré et l'arbre de dépendances réellement scanné.

Le SBOM est produit et publié par la chaîne d'intégration interne ; il n'est pas
committé dans ce dépôt et n'est pas attaché aux tags. Une demande de SBOM pour une
version publiée passe par les [contacts](#signaler-une-vulnérabilité) ci-dessus.

**Limite connue.** Les analyses ci-dessus vivent dans l'infrastructure de l'État :
un contributeur externe n'en dispose pas, et une pull request ouverte depuis
l'extérieur n'en déclenche aucune. Le point 4 de la
[charte open source](charte_open_source.md) demande qu'un code publié soit également
soumis à une analyse accessible depuis l'extérieur de l'OCSIN — typiquement
SonarCloud via une GitHub Action. Cette mise en place reste à faire.

## Gestion des secrets

**Aucun secret ne doit figurer dans ce dépôt, ni dans les sources ni dans
l'historique.** Conformément au point 3 de la
[charte open source](charte_open_source.md), la notion de secret ne se limite pas aux
mots de passe : les noms d'utilisateur, les noms de machine et les URL internes à
l'État en font partie.

### Comment les secrets sont fournis à l'application

Tous les secrets d'exécution sont injectés par variables d'environnement, résolues
par la plateforme depuis ses propres ressources de type *secret* — jamais depuis un
fichier du dépôt :

- `S3_ACCESS_KEY` / `S3_SECRET_KEY` — credentials du stockage objet
- `JWK_SET_URI` / `ISSUER_URI` — fournisseur d'identité pour la validation JWT
- `javax.net.ssl.trustStore` / `javax.net.ssl.trustStorePassword` — truststore TLS,
  positionnés par l'image de base à partir de variables de la plateforme

Le fichier `application.yml` versionné ne contient que des valeurs par défaut non
sensibles et des références de variables ; il ne porte aucun credential. Il ne
déclare que l'origine CORS de production, publique — les hôtes des environnements
non productifs sont injectés par `APP_CORS_ALLOWED_ORIGINS` et ne sont pas
versionnés.

### Ce qui est exclu du dépôt

Le [`.gitignore`](.gitignore) écarte notamment `/.m2/` — le `settings.xml` Maven, qui
porte les credentials du dépôt d'artefacts, est intégralement généré par la chaîne
d'intégration et n'a plus à exister dans l'arborescence de travail — ainsi que
`/docs/security/`, qui reçoit les rapports d'analyse et de test d'intrusion. Ces
documents ne sont pas publiables : ils décrivent des faiblesses avant correction.

Sont également hors de ce dépôt, par construction et non par exclusion : la
définition du pipeline, le Dockerfile de production et tout ce qui nomme
l'infrastructure de l'État. Ces éléments vivent dans un dépôt interne distinct qui
référence celui-ci comme sous-module (cf.
[Où vit la chaîne de build](CONTRIBUTING.md#où-vit-la-chaîne-de-build)).

### Si un secret a fuité

Un secret committé doit être considéré comme compromis dès la poussée, y compris
après suppression : l'historique Git le conserve, et un dépôt public est indexé.
La marche à suivre est, dans cet ordre :

1. **Révoquer et faire tourner** le secret concerné (credentials S3, jeton de dépôt
   d'artefacts, etc.) — c'est la seule action qui referme réellement l'exposition.
2. Signaler la fuite par les [canaux ci-dessus](#signaler-une-vulnérabilité) : un
   secret résiduel relève de la même procédure qu'une vulnérabilité.
3. Purger l'historique. Cette opération réécrit les références et se mène avec
   l'équipe des Moyens de développement (OCSIN-DSOL), comme le prévoit la charte.

Ne vous contentez jamais d'un commit de suppression : il ne retire rien de
l'historique et signale précisément quoi chercher.

## Périmètre

**Dans le périmètre** — le code de ce dépôt et son comportement en exécution :
traitement des fichiers téléversés (images et PDF), conversion et découpe PDF,
gestion des sessions et des tokens, contrôles d'accès des endpoints REST et SSE,
validation JWT du proxy de récupération de document, configuration par défaut livrée
dans `application.yml`.


## Mesures en place

Ces mécanismes sont documentés dans le [README](README.md) ; ce résumé sert à cadrer
un rapport et à éviter les doublons sur des comportements volontaires.

- **Antivirus.** Tout fichier téléversé est scanné par ClamAV (INSTREAM) avant
  stockage. En production le `fail-mode` est `closed` : l'indisponibilité de `clamd`
  fait échouer le téléversement plutôt que de stocker un fichier non scanné.
- **Contrôle du type de fichier.** Le routage PDF/image se fait sur les *magic bytes*
  du contenu, pas sur le `Content-Type` déclaré par le client, et une liste blanche
  de formats est appliquée.
- **Bornes anti-DoS.** Plafonds sur la taille multipart, le nombre de pages et les
  dimensions d'image ; limitation de débit par IP sur la création de session ; verrou
  « une session par IP d'origine » ; contrôle d'admission (bulkhead) sur le
  téléversement. Ghostscript est invoqué avec `-dSAFER`.
- **Isolation de la surface de gestion.** Actuator est exposé sur un port distinct
  (9000) du trafic métier (8080) ; `/actuator/**` et la Swagger UI ne sont pas joignables depuis Internet.
- **Contrôle d'origine.** Verrou same-origin côté serveur sur les endpoints publics,
  CORS restreint aux origines déployées. Le profil de taille de session est dérivé de
  l'`Origin` estampillé par le navigateur, non d'une valeur fournie par le client.
- **Récupération du document final.** `GET /api/documents/{documentId}` exige un JWT
  OIDC validé via JWKS avec l'audience `AEL.TELEVERSE`. L'appel est *one-shot* : le
  PDF est supprimé de S3 dès la réponse écrite avec succès.
- **Exécution du conteneur.** L'image applicative tourne sous un utilisateur non
  privilégié (UID 185), à partir d'une image de base UBI maintenue par la plateforme.
- **Cycle de vie des données.** Les sessions portent un TTL ; les pages S3 sont
  purgées à la suppression explicite, à l'expiration et à l'éviction. Les vignettes
  vivent dans un cache Hazelcast jetable, jamais sur S3.
