# Contribuer à GE-Televerse-Back

Merci de l'intérêt que vous portez à ce projet. Ce document décrit comment signaler
un problème, proposer une évolution et soumettre une contribution.

GE-Televerse-Back est développé par l'État de Genève pour ses formulaires en ligne.
Le code est publié sous licence Apache 2.0 : les contributions externes sont
bienvenues, dans les limites décrites à la section
[Contraintes de build](#contraintes-de-build-pour-les-contributeurs-externes).

## Code de conduite

Restez respectueux et constructif dans toutes les interactions. L'objectif est de
maintenir un environnement accueillant pour tout le monde.

## Comment contribuer

### Signaler un bug

> **Une faille de sécurité ne se signale pas par une issue publique.** Suivez la
> procédure décrite dans [SECURITY.md](SECURITY.md).

- Vérifiez d'abord les issues existantes pour éviter les doublons.
- Utilisez un titre clair et descriptif.
- Décrivez les étapes de reproduction, le comportement attendu et le comportement
  observé.
- Précisez la version concernée (tag `vX.Y.Z`), le profil (`dev` ou production) et,
  si le problème porte sur le traitement d'un document, le type de fichier en entrée
  (image ou PDF, mono ou multi-pages) — **sans joindre de document contenant des
  données personnelles**.
- Joignez les logs pertinents. Ils sont au format `clé=valeur` ; un extrait avec le
  `token` de session anonymisé suffit généralement.

### Proposer une évolution

- Ouvrez une issue décrivant le cas d'usage avant d'écrire du code : le périmètre
  fonctionnel est piloté par le métier (cf. [Contacts](README.md#contacts)) et une
  évolution utile techniquement n'est pas toujours retenue.
- Expliquez le comportement attendu et son articulation avec l'existant.
- Les évolutions touchant au contrat d'API (nouveaux endpoints, nouveaux codes
  d'erreur) doivent s'accompagner d'une mise à jour de `docs/api.md` et de la
  spécification OpenAPI.

### Soumettre une pull request

1. **Forkez et clonez le dépôt**

   ```bash
   git clone https://github.com/<votre-compte>/ge-televerse-back.git
   cd ge-televerse-back
   ```

2. **Créez une branche depuis `develop`**

   `develop` est la branche d'intégration ; `main` ne reçoit que les releases.

   ```bash
   git checkout develop
   git checkout -b feat/description-courte
   ```

3. **Préparez l'environnement de développement**

   Prérequis : **Java 21+** et **Maven 3.9+**. Le profil `dev` démarre sans
   dépendance externe (Hazelcast embarqué, stockage local, antivirus désactivé).

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=dev
   ```

   Ghostscript (`gs`, résolu via le `PATH`) est optionnel en développement :
   sans lui, l'application démarre, la recompression est désactivée et la découpe
   des PDF multi-pages retombe sur PDFBox. Cette bascule dégrade les polices en
   sous-ensemble — ne validez donc jamais un comportement de découpe sans `gs`.

4. **Écrivez le code**

   Respectez les [conventions ci-dessous](#conventions-de-code) et documentez ce qui
   n'est pas déductible du code : un choix contre-intuitif mérite un commentaire qui
   explique *pourquoi*, pas *quoi*.

5. **Testez**

   ```bash
   mvn test                                  # suite complète
   mvn test -Dtest=PdfServiceTest            # une classe
   mvn test -Dtest=PdfServiceTest#shouldConvertJpegToPdf   # une méthode
   ```

   Toute correction de bug doit venir avec un test qui échoue sans le correctif.

6. **Committez et poussez**

   ```bash
   git commit -m "feat: description de votre changement"
   git push origin feat/description-courte
   ```

7. **Ouvrez la pull request**

   - Ciblez `develop`, jamais `main`.
   - Décrivez le changement et référencez l'issue associée.
   - Signalez explicitement tout impact sur le contrat d'API, la configuration ou
     les métriques exposées.

## Conventions de code

### Langue

Le français est utilisé dans les messages de commit, les commentaires et la
documentation. Les identifiants de code (classes, méthodes, variables) restent en
anglais, comme le reste de l'écosystème Java.

### Style

- **Modèles** — des `record` Java, pas des classes avec accesseurs.
- **Configuration** — `@ConfigurationProperties` sur des records. N'utilisez pas
  `@Value` pour lier une liste YAML : cette combinaison est fragile et a déjà cassé
  lors d'une montée de version de Spring.
- **Réactif** — toutes les méthodes de contrôleur renvoient `Mono<>` ou `Flux<>`.
  Aucune I/O bloquante sur l'event loop Netty : le travail CPU (conversion PDF,
  OpenCV, Ghostscript) part sur `Schedulers.boundedElastic()`.
- **Verrous Hazelcast** — `IMap.lock()` est *thread-affine* : le `unlock` doit venir
  du thread qui a pris le verrou. Les sections critiques s'exécutent sur un
  scheduler dédié, pas sur l'event loop.
- **Chemin de base REST** — `/api`, positionné via `spring.webflux.base-path` ; ne le
  répétez pas dans les annotations de mapping.
- Alignez-vous sur le style du fichier que vous modifiez : densité de commentaires,
  nommage, idiomes.

### Messages de commit

Format *conventional commits*, avec une portée optionnelle :

- `feat:` — nouvelle fonctionnalité
- `fix:` — correction de bug
- `perf:` — amélioration de performance
- `refactor:` — remaniement sans changement de comportement
- `docs:` — documentation
- `test:` — tests
- `build:` / `ci:` — chaîne de build et d'intégration
- `chore:` — maintenance

Exemples tirés de l'historique : `fix(pdf): découpe les PDF multi-pages via
Ghostscript`, `feat(sessions): token bucket par IP sur POST /sessions, anti-DoS`.

Le message doit dire ce que le changement produit, pas quels fichiers il touche.

### Branches et versionnage

- Flux Git : `develop` → `main`. Les releases sont taguées `vX.Y.Z` et fusionnées
  sur `main`.
- Versionnage sémantique. **La version n'est jamais commitée dans le POM** : elle est
  calculée depuis les tags Git par la chaîne d'intégration et injectée via
  `-Drevision` (versionnage ci-friendly, avec `${revision}` et
  `flatten-maven-plugin`). Ne modifiez pas `<version>` dans une PR.
- Toute contribution visible par un utilisateur ou un exploitant doit être ajoutée à
  la section `Non publié` du [CHANGELOG](CHANGELOG.md), dans la rubrique appropriée
  (`Ajouté`, `Modifié`, `Corrigé`, `Sécurité`).

## Tests

Les tests sont des tests unitaires purs avec Mockito, sans contexte Spring chargé.
Deux exceptions à connaître :

- `PdfServiceTest` effectue de véritables conversions PDFBox.
- Les tests qui dépendent de bibliothèques natives (OpenCV, ONNX Runtime,
  Ghostscript) se désactivent d'eux-mêmes lorsque la bibliothèque est absente. Un
  test « vert » sur une machine sans ces dépendances ne prouve donc rien sur ces
  chemins : vérifiez qu'ils ont réellement tourné avant de conclure.

Le plugin surefire exige l'agent Mockito (`-javaagent:${org.mockito:mockito-core:jar}`,
résolu par `maven-dependency-plugin:properties`). Il est déjà configuré dans le POM ;
si vous lancez les tests depuis un IDE, veillez à ce qu'il reprenne la configuration
surefire.

## Structure du projet

Le détail package par package figure dans la section
[Structure du projet](README.md#structure-du-projet) du README. En résumé :

- `config/` — clients S3 et Hazelcast, CORS, propriétés typées
- `controller/` — endpoints REST et flux SSE, gestionnaire d'erreurs global
- `service/` — logique métier : sessions, stockage, PDF, image, antivirus
- `model/` — records du domaine

## Contraintes de build pour les contributeurs externes

Conformément au point 6 (« Isolation ») de la
[charte open source de l'État](charte_open_source.md), voici ce qui est reproductible
hors de l'infrastructure de l'État et ce qui ne l'est pas :

- **Build Maven et tests** — reproductibles, sous réserve que les dépendances soient
  résolues depuis Maven Central.
- **Exécution en profil `dev`** — reproductible : Hazelcast embarqué, pas de S3, pas
  de ClamAV, CORS relâché.
- **Build de l'image Docker** — reproductible via le [`Dockerfile`](Dockerfile) de ce
  dépôt, qui part d'une image OpenJDK 21 publique et installe Ghostscript. C'est
  l'image de développement : elle sert à exercer le service en conteneur, pas à
  produire le livrable déployé.
- **Image de production** — non reproductible : elle est construite par la chaîne
  d'intégration de l'État à partir d'une image de base durcie, hébergée sur un
  registre interne inaccessible depuis l'extérieur. Ce build ne vit pas dans ce
  dépôt (cf. [Où vit la chaîne de build](#où-vit-la-chaîne-de-build)).
- **Exécution en configuration de production** — non reproductible : elle suppose un
  cluster Hazelcast, un service ClamAV, un stockage S3 et un fournisseur d'identité
  OIDC.

### Où vit la chaîne de build

Ce dépôt porte **le code source, sa documentation et son build Maven** — c'est le
dépôt de référence pour toute contribution.

La **chaîne d'intégration interne** (pipeline, image de production, publication de
l'artefact, analyses de sécurité) vit dans un dépôt distinct, non publié, qui
référence celui-ci comme sous-module. Cette séparation existe pour que rien de ce
qui décrit l'infrastructure de l'État — noms de machines, URL de registres, clés de
publication — n'atterrisse dans un dépôt public, conformément au point 3 de la
charte.

Conséquence pratique pour un contributeur : `mvn verify` et le `Dockerfile` de ce
dépôt sont tout ce dont vous avez besoin ; l'absence de fichier de pipeline ici
n'est pas un oubli.

## Revue

Toute pull request est relue avant fusion. La revue porte sur le comportement, les
tests, l'impact sur le contrat d'API et la sécurité. Conformément au point 9 de la
charte, une attention particulière est portée aux contributions susceptibles
d'introduire une faille — élargissement silencieux d'une liste blanche, contournement
d'une borne anti-DoS, ajout d'une dépendance non nécessaire. Ce n'est pas une
présomption de mauvaise foi, c'est la procédure.

Attendez-vous à des allers-retours sur les PR qui touchent au traitement des fichiers
entrants ou aux contrôles d'accès : c'est la surface d'attaque du service.

## Questions

Ouvrez une issue, ou écrivez au contact technique indiqué dans la section
[Contacts](README.md#contacts) du README.

## Licence

En contribuant, vous acceptez que vos contributions soient publiées sous
[licence Apache 2.0](LICENSE), comme le reste du projet.
