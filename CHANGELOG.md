# Changelog

Toutes les évolutions notables de GE-Televerse-Back sont consignées dans ce fichier.

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le
versionnage suit [SemVer](https://semver.org/lang/fr/). Les versions correspondent
aux tags `vX.Y.Z` du dépôt ; la version est calculée depuis ces tags par la chaîne
d'intégration et n'est jamais commitée dans le POM.

## [Non publié]

### Ajouté

- Fichiers de gouvernance open source : `CHANGELOG.md`, `CONTRIBUTING.md`,
  `SECURITY.md` et copie de la charte open source de l'État
  (`charte_open_source.md`).
- Section « Contacts » et « Gouvernance » dans le README.
- `Dockerfile` public, sur base OpenJDK 21 + Ghostscript, pour construire et
  exercer le service en conteneur hors de l'infrastructure de l'État.

### Modifié

- Documentation : README réaligné sur l'architecture réelle (Hazelcast, création de
  session portée par le web component), diagramme Mermaid de l'architecture avec
  numérotation des flux, documentation de ClamAV et de Ghostscript, endpoints
  manquants complétés.
- CI : suppression de `.m2/settings.xml`, désormais généré par le pipeline.
- Nettoyage des URL internes dans les sources et casts explicites manquants dans un
  test unitaire.
- **Séparation du dépôt en vue de la publication.** Les sources, leur documentation
  et le build Maven vivent désormais dans ce dépôt public ; la chaîne d'intégration,
  le Dockerfile de production et les propriétés Sonar restent dans un dépôt interne
  qui référence celui-ci comme sous-module. Aucun changement de comportement.
- `app.cors.allowed-origins` ne déclare plus que l'origine de production. Les
  environnements non productifs passent par l'env var `APP_CORS_ALLOWED_ORIGINS`
  (CSV), posée par leur manifeste de déploiement.
- **Montée des dépendances Maven.** Spring Boot 4.0.6 → 4.1.0, SDK AWS 2.44.12 →
  2.50.2, PDFBox 3.0.7 → 3.0.8, metadata-extractor 2.19.0 → 2.21.0, Netty 4.2.15 →
  4.2.16, plus les plugins springdoc (1.4 → 1.5) et flatten (1.6.0 → 1.7.3).
  OpenCV/javacpp (1.5.11) et ONNX Runtime (1.20.0) sont **volontairement laissés en
  arrière** : leurs paliers 1.5.13 et 1.27.0 cassent le chargement des natives sous
  Windows (cf. commentaires dans le POM). Suite complète verte, 333 tests.
- **springdoc 3.0.3 → 3.1.0**, dernier palier disponible (swagger-core 2.2.47 →
  2.2.52). La spec régénérée est identique à celle en place : la montée ne change
  rien au contrat publié. Elle n'affranchit pas non plus de Jackson 2 —
  swagger-core reste sur la ligne 2.x et demeure la seule dépendance qui l'amène,
  d'où la propriété `jackson-2-bom.version` maintenue dans le POM.
- Spring Boot 4.1 charge le truststore d'un bundle SSL dès le rafraîchissement du
  contexte, là où 4.0 le différait. Le bundle pointe sur
  `${javax.net.ssl.trustStore}`, propriété posée par l'image de production mais
  absente de la JVM de test : les tests chargeant le contexte complet échouaient sur
  le placeholder non résolu. Surefire pose désormais le truststore factice du profil
  dev. **À surveiller en préproduction** : un chemin de truststore erroné fait
  maintenant échouer le démarrage du pod au lieu de se manifester plus tard.
- **Dimensions arrondies au lieu de tronquées** pour le redimensionnement des
  images, la hauteur des vignettes et la taille de l'image redressée : un pixel
  d'écart au plus (4000×3000 à 150 dpi donne 1754×1316 au lieu de 1754×1315 ;
  vignette d'une page A4 : 120×170 au lieu de 120×169). Les tailles de noyau de la
  détection de document restent tronquées, leurs ratios ayant été calibrés sur ces
  valeurs.
- **Plus de Swagger UI dans l'application déployée.** springdoc, qui servait la spec
  et la Swagger UI sur le port de gestion (`:9000/actuator/openapi`,
  `/actuator/swagger-ui`), amenait Jackson 2 par swagger-core — qui n'existe pas sur
  Jackson 3 — et avec lui les constats NexusIQ de la ligne 2.x à chaque rapport. Il ne
  vit plus que dans le profil Maven `openapi-generation`, qui génère
  `docs/openapi/` et sert la Swagger UI en local (cf. README). Le jar de production
  ne contient plus que Jackson 3. La spec est régénérée : elle expose désormais les
  champs `status` et `statusUntil` de la session.
- **Spring Boot 4.1.1.** Le parent embarque désormais Spring Framework 7.0.9, Spring
  Security 7.1.1, Micrometer 1.17.1, Reactor 2025.0.7 et logback 1.5.38 : les cinq
  surcharges posées à la main pour les constats AppSec sont retirées, aux mêmes
  versions résolues. Restent surchargés, au-dessus de Boot : Netty 4.2.18, log4j
  2.26.1, Jackson 3.2.2 et 2.22.2. Hazelcast reste en 5.4.0 (contrainte de licence,
  désormais documentée dans le POM), et la dépendance explicite à
  `spring-boot-starter-reactor-netty`, déjà tirée par le starter webflux, disparaît.
- **Couverture de tests publiée dans Sonar.** Aucun rapport JaCoCo n'était produit :
  Sonar affichait 0 % de couverture et la quality gate restait rouge. Le rapport est
  généré dès la phase `test`, l'agent Mockito ne supplantant plus celui de JaCoCo.
- **Pool de 5 threads pour les tâches planifiées.** Heartbeats SSE, balayage des
  sinks, sonde antivirus, moniteur `/tmp` et éviction du rate-limit partageaient un
  thread unique : un balayage ralenti par Hazelcast suspendait les heartbeats, et
  proxies comme watchdog du front coupaient alors tous les flux SSE du pod.

### Corrigé

- **Éditions de page cohérentes sous concurrence, finalisation atomique.** Une
  édition réécrivait le PDF vivant et son `-prev` en place AVANT de prendre le verrou
  de session : un rejeu après `429 SESSION_BUSY` tournait la page une seconde fois,
  deux éditions concurrentes se perdaient, et l'édition « réussissait » (200
  « appliqué ») sur une page supprimée entre-temps. Chaque édition écrit désormais
  sa version sous des clés neuves, puis la session l'adopte sous verrou après avoir
  vérifié que la page existe toujours, qu'elle n'a pas changé depuis la lecture
  (`409 PAGE_GONE` / `409 PAGE_CHANGED`) et que le quota tient (`413
  SESSION_SIZE_EXCEEDED`, auparavant ignoré). Undo et « Annuler tout » font
  pointer la page sur la version visée, sans copie S3. Une version refusée est
  supprimée ; les clés abandonnées sont purgées après adoption.
  La session porte un statut `OPEN` → `FINALIZING` → `FINALIZED` : pendant la
  fusion, ajout, édition, suppression et déplacement de page répondent `409
  SESSION_FINALIZING` — une page ajoutée à ce moment était perdue en silence. Une
  finalisation rejouée rend le même `documentId` ; un second appel concurrent reçoit
  `429 FINALIZE_BUSY`. Les pages ne sont plus purgées qu'une fois la session passée
  en `FINALIZED` : purgées dans le chemin de succès de la fusion, un DELETE en échec
  faisait échouer la finalisation, et chaque rejeu échouait ensuite sur les pages
  déjà supprimées. Une fusion en échec, hors délai ou abandonnée par le client
  rouvre la session ; le bail de finalisation libère celle d'un pod mort.
  La suppression retire la page de la session avant de purger ses objets, et le
  réordonnancement revérifie la liste sous verrou (`409 PAGES_CHANGED`) : une page
  attachée entre-temps disparaissait de la session, et une page supprimée
  entre-temps y laissait un `null` rendant le token illisible jusqu'à expiration.
  Un upload refusé sans écriture (verrou non obtenu, session en finalisation)
  supprime la page déjà stockée. Les sessions en vol lors du déploiement sont lues
  comme `OPEN`.
- **Retouches d'image refusées sur les pages de PDF natif.** Recadrage,
  redressement et balance des blancs reconstruisent la page à partir de sa première
  image : sur une page de PDF portant du texte, des tracés ou plusieurs images, tout
  le reste disparaissait en silence. Ces retouches répondent désormais `422
  PAGE_NOT_EDITABLE` sans rien modifier ; photos et pages numérisées (y compris
  après passage par Ghostscript) restent éditables, et la rotation s'applique à
  toute page.
- **Flux SSE : ouverture hors event loop, reconnexions sans perte.** L'ouverture
  d'un flux lisait la session par un appel Hazelcast bloquant sur le thread
  d'event loop ; un Hazelcast lent gelait le pod à chaque vague de reconnexions. Un
  sink remplacé (reconnexion sur le même pod) n'était pas complété, et l'annulation
  tardive de l'ancienne connexion fermait le flux vivant. Deux émissions simultanées
  (heartbeat et événement) perdaient l'événement en silence. Session absente : `404`
  au lieu d'un flux vide qui faisait reconnecter EventSource toutes les ~3 s. Le
  balayage des sinks expirés lit par lots.
- **Timeout Ghostscript effectif.** Le timeout Reactor laissait `gs` tourner et le
  worker bloqué sur la lecture de sa sortie jusqu'à ce que le process termine de
  lui-même : quelques PDF lourds suffisaient à occuper tous les workers, et les PDF
  multi-pages retombaient sur le découpage PDFBox, qui corrompt les accents. La
  sortie passe par un fichier, l'attente est bornée, `gs` et ses descendants sont
  tués à l'échéance, et chaque appel travaille dans son propre répertoire
  (permissions propriétaire seul), supprimé même après un kill.
- **Purge des sessions expirées : un seul pod, concurrence bornée.** Chaque pod
  recevait chaque expiration et lançait sa propre purge, un DELETE non borné par
  objet : une rafale d'expirations saturait le pool S3 de tous les pods et faisait
  échouer uploads et finalisations des usagers actifs. Un pod est désormais élu par
  session, et la purge tient au plus 4 DELETE en vol.
- **Régénération de vignette soumise à l'admission.** Sur défaut de cache (Hazelcast
  injoignable, cache désactivé), chaque vignette déclenchait le téléchargement et le
  rendu de sa page sans limite de concurrence. La régénération passe par le même
  bulkhead que l'aperçu (`429 MERGE_BUSY` à saturation) ; un rendu en échec répond
  `422 CORRUPT_FILE` au lieu d'un `503` rejoué indéfiniment.
- **Mémoire du chemin image → PDF divisée par trois.** Une photo était décodée à
  pleine résolution et orientée avant d'être réduite : 624 Mo de tas pour une photo
  de 75 MP, soit ~5 Go pour les 8 uploads admis par pod. Décodage sous-échantillonné,
  réduction puis orientation : 200 Mo. Le rendu PDF sous-échantillonne les images
  embarquées, et une image déclarant plus de 250 MP est refusée avant décodage (elle
  finissait en `OutOfMemoryError`).
- **Rotation conservée au recadrage et au redressement.** Ces éditions reconstruisent
  la page depuis l'image embarquée, jamais tournée : une page tournée puis recadrée
  revenait de travers.
- **Préflight CORS de `/documents/**`.** Le filtre CORS s'exécutait après
  l'autorisation Spring Security : le préflight d'un `GET /documents/{id}` (en-tête
  `Authorization`) recevait un 401 sans en-tête CORS, et aucun navigateur n'envoyait
  la requête. La configuration CORS passe par la chaîne Security, avant
  l'autorisation.

- **`HazelcastOverloadException` sur `/finalize` en charge.** La purge du cache de
  vignettes en fin de session émettait un appel `deleteAll` par page, chacun
  fire-and-forget et laissant `flatMap` prendre sa concurrence par défaut (256) : une
  session de 20 pages déclenchait jusqu'à 60 invocations Hazelcast simultanées, à
  multiplier par les finalisations concurrentes admises
  (`app.admission.finalize.max-concurrent`). Le pic dépassait de deux ordres de
  grandeur `hazelcast.client.max.concurrent.invocations`, et la saturation retombait
  sur *toutes* les opérations Hazelcast du pod, pas seulement la purge. Les clés
  d'une session sont désormais purgées en un seul appel, à concurrence bornée : le
  coût d'une purge est constant au lieu d'être proportionnel au nombre de pages.
- **Démarrage local impossible en profil `dev`.** `mvn spring-boot:run
  -Dspring-boot.run.profiles=dev` échouait sur `jwkSetUri cannot be empty`, alors
  même qu'une exclusion d'auto-configuration était en place pour empêcher
  exactement ce cas. Depuis Boot 4, le décodeur réactif et le décodeur servlet sont
  portés par deux auto-configurations distinctes, et celle de base
  (`OAuth2ResourceServerAutoConfiguration`) n'est conditionnée qu'à la présence de
  `BearerTokenAuthenticationToken` — pas au type d'application. Elle s'appliquait
  donc aussi à cette application réactive et y créait un `JwtDecoder` servlet dont
  personne ne veut, sur le `jwk-set-uri` vide de `${JWK_SET_URI:}`. Elle est
  désormais exclue elle aussi, dans la configuration principale comme dans le
  profil `openapi` (dont la liste ne fusionne pas avec la principale). Sans effet
  en production, où `JWK_SET_URI` est renseignée.
- **Spec OpenAPI publiée obsolète.** `docs/openapi/openapi-ge-televerse.yaml`
  n'avait plus été régénérée depuis la ligne 0.2.x. Il y manquait l'endpoint
  `POST /sessions/{token}/pages/{pageId}/reset` ainsi que les champs
  `contentHash` et `initialVersion` de `PageInfo`, tous présents dans le code
  depuis. Régénérée avec
  `mvn verify -Popenapi-generation -Dopenapi-generation=true -Drevision=…` — le
  `-Drevision` est nécessaire, faute de quoi `info.version` retombe sur le
  `0.0.0-LOCAL-SNAPSHOT` des builds locaux (la version vient de `BuildProperties`,
  cf. `OpenApiConfiguration`).
- **Recadrage élargi quand un coin sort du cadre.** Le rectangle englobant du
  recadrage mesurait sa largeur depuis le coin hors cadre avant de la borner à
  l'image : un quadrilatère débordant de 30 px à gauche donnait un recadrage 30 px
  trop large à droite. Les deux bords sont désormais bornés indépendamment. En
  contrepartie, un recadrage sur coordonnées fractionnaires gagne 1 à 2 px, les
  pixels partiellement couverts étant conservés.

### Sécurité

- **Rapport NexusIQ du 24.09.2026** (après le passage à Spring Boot 4.1.1) :
  - Jackson 3.2.3 et 2.22.3 : CVE-2026-89425 (jackson-core, lecture par
    `DataInput`), CVE-2026-89407 (ReDoS de `NumberInput`, 2.x), CVE-2026-91776 et
    CVE-2026-91777 (databind).
  - SDK AWS 2.54.18 → 2.55.4 : le SDK embarque sa propre copie relocalisée de
    jackson-core, que les propriétés Jackson ne touchent pas — 2.21.4, affectée par
    CVE-2026-68498 (`maxNameLength` appliqué trop tard), désormais 2.21.7.
  - log4j-api retiré du classpath, avec le pont `log4j-to-slf4j` qui l'amenait
    (sonatype-2026-006746, sans version corrigée) : aucune bibliothèque ne
    journalise par l'API Log4j, toutes les références sont des détections
    facultatives qui retombent sur SLF4J.
  - logback 1.5.38 → 1.6.3 : CVE-2026-19880 (chemin de fichier via le
    `MDCDiscriminator` d'un `SiftingAppender`, non employé ici), que la ligne 1.5 ne
    corrige pas. Boot 4.1 manage la 1.5.x et n'est pas testé contre la 1.6 : suite
    complète et démarrage réel sur la configuration de production validés, à
    revalider à chaque montée de Boot.
  - Qualifiés non atteignables : spring-security-crypto
    CVE-2026-47842 (7.1.1 hors de la plage affectée ; `AesBytesEncryptor` non
    employé) ; spring-security-web CVE-2026-47838 et HdrHistogram CVE-2026-14683 /
    CVE-2026-14686, déjà qualifiés.
  - Dérogation — metadata-extractor 2.21.0, dernière version (sonatype-2021-4936,
    -4937, -4939, -4949) : déni de service sur des métadonnées d'image malformées.
    Atteignable — la bibliothèque lit l'orientation EXIF de chaque image reçue —
    mais borné : toute exception retombe sur l'orientation 1, l'entrée est
    plafonnée à 50 Mo et l'upload passe par son bulkhead.
  - Dérogation temporaire — Hazelcast 5.4.0 (CVE-2025-52999, sonatype-2022-6438) :
    contrainte de licence, cf. POM.
- **`documentId` indépendant du token de session, tokens masqués dans les logs.**
  Le `documentId` valait `<token>.pdf` : l'identifiant remis à l'application hôte
  (postMessage, sa base, ses logs) était le jeton porteur de la session, et le token
  présent dans chaque URL journalisée donnait l'identifiant du document. Il est
  désormais tiré au hasard, sous la même forme `<uuid>.pdf` ; les sessions en vol lors
  du déploiement gardent le leur. Tout UUID est en outre réduit à ses 8 premiers
  caractères dans les logs — messages et traces d'exception, bibliothèques comprises —
  ce qui suffit à corréler les lignes d'une session.
- **Secrets masqués dans le dump HTTP de niveau DEBUG.** `HttpLoggingFilter`
  écrivait tous les en-têtes, dont `Authorization: Bearer <jwt>` et `Cookie` : un
  `LOGLEVEL_HTTP=DEBUG` posé en incident versait des JWT dans les logs, avec
  lesquels quiconque les lit pouvait consommer un document à usage unique avant
  l'application hôte. `Authorization`, `Proxy-Authorization`, `Cookie` et
  `Set-Cookie` sont remplacés par « [masqué] ».
- **Solde des vulnérabilités remontées par Nexus IQ** : treize constats sur huit
  coordonnées, toutes transitives. Spring Boot 4.1.0 étant le dernier palier de sa
  ligne, les cinq dépendances qu'il manage sont corrigées par surcharge de propriété
  du BOM — le mécanisme déjà en place pour Netty : logback 1.5.34 → 1.5.38
  (CVE-2026-13006), log4j 2.25.4 → 2.25.5 (CVE-2026-49844), Jackson 3.1.4 → 3.1.5 et
  Jackson 2.21.4 → 2.21.5 (CVE-2026-54514, CVE-2026-54515, CVE-2026-59889).
- **Retrait du client HTTP synchrone du SDK AWS.** L'exclusion posée sur `awssdk:s3`
  visait `apache-client`, nom que le SDK a depuis abandonné au profit
  d'`apache5-client` : elle ne mordait plus, et le client synchrone était revenu sur
  le classpath avec `httpclient5` → `httpcore5` / `httpcore5-h2` (CVE-2026-54399,
  CVE-2026-54428). Seul `S3AsyncClient` est utilisé, sur un `NettyNioAsyncHttpClient`
  construit explicitement : ces deux CVE disparaissent par suppression de la
  dépendance, et non par montée de version.
- **Deux constats qualifiés non atteignables**, faute de version corrigée à viser :
  `spring-security-web` 7.1.0 (CVE-2026-47838) — l'avis Spring ne liste aucune 7.x
  parmi les versions affectées, et la classe visée relève de la pré-authentification
  par certificat X.509, que l'application ne configure pas (elle authentifie par JWT
  via le resource server OIDC) ; `HdrHistogram` 2.2.2 (CVE-2026-14683,
  CVE-2026-14686) — aucune version corrigée n'existe en amont, et la fonction visée
  décode un histogramme sérialisé fourni par un tiers, là où l'application se borne à
  produire des métriques Micrometer sans histogramme de percentiles.
- **Montées de dépendances pour les constats AppSec et Nexus IQ suivants** : Spring
  Framework 7.0.9, Spring Security 7.1.1, Micrometer 1.17.1, Reactor 2025.0.7
  (reactor-core 3.8.7, reactor-netty 1.3.7), Netty 4.2.18, log4j 2.26.1, Jackson
  3.2.2 et 2.22.2, SDK AWS 2.54.18. Reactor passe par la propriété de son BOM, ce qui
  réaligne aussi `reactor-test`. Spring Boot 4.1.1 embarque désormais Framework,
  Security, Micrometer, Reactor et logback à ces versions : monter le parent
  permettra de retirer ces surcharges.
- **Expression régulière vulnérable au ReDoS** (règle Sonar S5852) dans la
  condensation des messages de timeout Hazelcast (`ExceptionMessages`) : ses `.*?`
  successifs pouvaient faire exploser le retour arrière sur un message forgé. Elle est
  réécrite en jetons tempérés possessifs, en temps linéaire, et reste ancrée en début
  de message : sans l'ancre, une entrée hostile de 124 Ko coûte encore 8 s.
- **Plafond d'upload multipart inopérant.** Les limites posées par
  `WebFluxMultipartConfig` (une part, 50 Mo par fichier, en-têtes de 8 Ko) étaient
  réécrites par le `CodecCustomizer` de Spring Boot, qui applique ses propres défauts
  — illimités — à tout `DefaultPartHttpMessageReader`, y compris un lecteur configuré
  à la main. Un envoi de plusieurs Go était donc écrit en entier sur le disque
  temporaire du pod avant tout contrôle, et un fichier au-delà de 60 Mo dépassait le
  flux que l'antivirus sait scanner. Les limites passent désormais par
  `spring.webflux.multipart.*` (`max-disk-usage-per-part` référence
  `app.limits.multipart-ceiling`) et la classe est supprimée. Un fichier trop gros
  reçoit `413 FILE_TOO_LARGE`, un envoi à plusieurs parts `400 INVALID_MULTIPART`
  (auparavant 500). Couvert par un test de bout en bout à travers le vrai pipeline
  WebFlux.

## [1.0.9] — 2026-07-28

### Modifié

- Les vignettes de page sont servies depuis un cache Hazelcast (`televerse-thumbnails`)
  au lieu de S3. Motif : la vignette est l'objet le plus lu du flux (une requête par
  page à chaque ouverture de la liste) pour un contenu qui ne survit pas à la session ;
  le trafic correspondant décrochait vers 4000 usagers simultanés. Il s'agit d'un cache
  et non d'une source de vérité — toute vignette reste reconstructible depuis le PDF de
  la page, qui demeure sur S3.

## [1.0.8] — 2026-07-22

### Ajouté

- Métrique `televerse.preview.errors` (tags `op` × `cause`) sur le chemin aperçu, pour
  distinguer une saturation S3 d'un 404 légitime.

### Corrigé

- Aperçus et vignettes : `503` retryable au lieu d'un `404` silencieux en cas de panne S3.
- `HttpLoggingFilter` : access-log émis au niveau `INFO` et non `DEBUG`.

## [1.0.7] — 2026-07-22

### Ajouté

- Anti-spam : refus des pages téléversées en double (`429 DUPLICATE_PAGE`).

## [1.0.6] — 2026-07-22

### Corrigé

- Couleurs inversées (canaux R/B) après recadrage, correction de perspective et
  balance des blancs.
- Recadrage : ajout d'un étage Otsu pour les pages claires sans bord net.

## [1.0.5] — 2026-07-17

### Ajouté

- Contrôle d'admission bornant la pression sur le pool de connexions S3 sous forte
  charge.

### Modifié

- `finalize.max-concurrent` porté à 100 par défaut.
- Ajout d'exclusions KICS.

## [1.0.4] — 2026-07-10

### Ajouté

- Réinitialisation d'une page à son état d'origine au téléversement (annulation de
  toutes les éditions appliquées).

## [1.0.3] — 2026-07-07

### Modifié

- Routage PDF/image déterminé par les *magic bytes* du fichier plutôt que par le
  `Content-Type` déclaré par le client.

## [1.0.2] — 2026-07-07

### Corrigé

- Correctif du pipeline de build.

## [1.0.1] — 2026-07-07

### Modifié

- Migration vers un versionnage basé sur les tags Git : la version n'est plus
  commitée dans le POM.
- Nouveaux pipelines de build ; remise en place des contrôles de sécurité de la chaîne.
- Désactivation temporaire de la validation JWT.

## [1.0.0] — 2026-07-02

Première version majeure : périmètre fonctionnel complet (téléversement mobile,
édition de page, finalisation, récupération OIDC du PDF).

### Ajouté

- `DELETE /api/documents/{documentId}` — suppression explicite d'un document.
- Swagger UI interne et spécification OpenAPI servies sur le port de gestion (9000),
  donc injoignables depuis Internet ; serveur de dev ajouté à la liste des serveurs
  OpenAPI.
- Journalisation des origines hors liste blanche de profils de taille.

### Modifié

- Le profil de taille de session est dérivé de l'`Origin` de confiance estampillé par
  le navigateur, et non du corps de requête fourni par le client.

### Corrigé

- Découpe des PDF multi-pages déléguée à Ghostscript : le `Splitter` de PDFBox
  corrompt les polices en sous-ensemble (accents illisibles) et les flux ICC.
- Préservation de la transparence et alignement de la qualité de recompression
  Ghostscript sur celle du chemin image.
- Téléversement : les en-têtes du `FilePart` ne sont plus lus avant les rejets précoces.

## [0.2.17] — 2026-06-12

### Corrigé

- Téléversement : le motif réel du refus sous verrou est remonté sur le chemin PDF
  multi-pages (au lieu d'un motif générique).

## [0.2.16] — 2026-06-10

### Corrigé

- Purge des pages S3 sur toutes les voies d'expiration de session (`DELETE`, TTL,
  éviction), et non seulement sur la suppression explicite.
- Événement SSE `page-edited` émis à chaque édition de page.

### Modifié

- Documentation : le contrat d'empreinte du binding *originator* est figé et couvre
  désormais la reprise de session.

## [0.2.15] — 2026-06-08

### Sécurité

- Verrou same-origin côté serveur sur les endpoints publics.
- Montée de Netty 4.2.14 → 4.2.15 (CVE-2026-42587, `netty-codec-http`).

## [0.2.14] — 2026-06-08

### Sécurité

- Durcissement pré-pentest : bornes anti-DoS sur le traitement image et PDF,
  `-dSAFER` sur les invocations Ghostscript, plafond sur la taille multipart.
- Borne de sécurité sur la taille des vignettes.

### Corrigé

- Image inexploitable en édition : `422` au lieu de `500`.

## [0.2.13] — 2026-06-04

### Corrigé

- Fichier illisible ou corrompu traduit en `422 CORRUPT_FILE` au lieu de `500`.
- Refus des pages au-delà du plafond évalué sous le verrou (course sur la
  multi-sélection).

## [0.2.12] — 2026-06-03

### Ajouté

- Surface Actuator déplacée sur le port 9000, non routé vers l'extérieur.

### Corrigé

- Propagation du statut des `ResponseStatusException` au lieu d'un `500` générique
  (notamment les `404`).

## [0.2.11] — 2026-06-02

### Ajouté

- Limitation de débit par IP (token bucket) sur `POST /api/sessions`, anti-DoS.
- Page de démonstration.

### Modifié

- Profil `dev` : binding *originator* et limitation de débit désactivés.

## [0.2.10] — 2026-06-02

### Ajouté

- Scan antivirus des téléversements via ClamAV en INSTREAM, activé par défaut ;
  sonde de disponibilité de `clamd` au démarrage.
- Liste blanche des formats de fichiers acceptés et endpoint `GET /api/upload/formats`.

### Corrigé

- Antivirus : timeout dédié pour le scan INSTREAM (30 s par défaut).
- Antivirus : reconstruction du client après échec de la sonde, pour forcer la
  re-résolution DNS.
- Absorption des `HazelcastException` dans les jauges de métriques actives.

## [0.2.9] — 2026-05-27

### Ajouté

- Proxy OIDC `GET /api/documents/{documentId}` pour les applications hôtes, avec
  validation JWT via JWKS et audience `AEL.TELEVERSE`.
- `HEALTHCHECK` Docker sur `/actuator/health/liveness`.

### Modifié

- L'URL du QR code est dérivée du header `Origin` de l'appel front-end au lieu d'être
  configurée en dur.

### Corrigé

- Déclaration de `sonar.java.binaries` pour le scanner Sonar.

## [0.2.8] — 2026-05-27

### Ajouté

- Verrou « une session par IP d'origine » (via `X-Forwarded-For`) avec kill-switch.
- Observabilité : exposition des compteurs du pool de connexions Netty du SDK S3.

### Modifié

- Migration Spring Boot 3.5.7 → 4.0.6 ; retrait de `hazelcast-spring` et du starter
  cache inutilisés.
- CORS restreint aux origines effectivement déployées, `DELETE`/`PUT` débloqués.

### Sécurité

- Montée de Netty 4.2.12 → 4.2.14 (CVE).

### Corrigé

- Le client AWS Netty ignore `HTTPS_PROXY` : l'endpoint S3 est on-prem et le proxy
  egress de la plateforme provoquait des timeouts d'acquisition de connexion.

## [0.2.7] — 2026-05-20

### Ajouté

- Observabilité : `HealthIndicator` sur le répertoire temporaire, `UPLOAD_BUSY` taggé,
  `televerse.session.final.bytes` ventilé par profil de taille.

## [0.2.6] — 2026-05-20

### Corrigé

- Section critique exécutée sur un thread unique et verrou propagé à toutes les
  mutations de l'`IMap` (le verrou Hazelcast est thread-affine).
- Renouvellement de l'expiration intégré au verrou, fermant la course
  `renewSession` → `attach`.

## [0.2.5] — 2026-05-20

Version pivot : intégration de la chaîne de traitement d'image et de la recompression
PDF.

### Ajouté

- Détection des quatre coins du document par CNN (DocAligner + ONNX Runtime) en
  premier étage de la cascade de détection.
- Correction de perspective automatique (deskew) via OpenCV, en cascade multi-noyaux
  tolérante.
- Recadrage automatique de page (auto-crop).
- Balance des blancs OpenCV, exposée en traitement post-téléversement.
- Rotation de page à la demande (90° horaire / anti-horaire) et annulation de la
  dernière édition.
- Découpe des PDF multi-pages en pages individuelles, chacune éditable et
  réordonnable.
- Recompression PDF via Ghostscript en sous-processus.
- Aperçu de page à largeur configurable et aperçu du PDF avant finalisation.
- Gestion de session multi-device (keep-alive, connexion/déconnexion du téléphone).
- `documentId` basé sur l'UUID de session (`<uuid>.pdf`).
- Contrôle d'admission sur le téléversement via Resilience4j Bulkhead.
- Découverte Hazelcast par DNS, namespace du cluster paramétré par variable
  d'environnement.
- Heartbeat SSE toutes les 15 s.
- Métriques Micrometer sur la recompression PDF et la volumétrie ; jauges
  `televerse.gs.peak.rss.bytes` et `televerse.gs.current.rss.bytes` pour calibrer le
  nombre de workers Ghostscript.
- `errorCode` discriminant sur les rejets de téléversement ; contrat
  `SESSION_SIZE_EXCEEDED` sur les `413`.
- `expiresAt` dans les réponses des mutations de session.
- DPI et qualité JPEG configurables pour les images entrantes ; orientation A4 alignée
  sur l'image source (portrait ou paysage).
- Mode dev local sans S3 ni Docker.
- Journalisation des requêtes HTTP entrantes sur un topic dédié.

### Modifié

- Réservation et attachement de page via `IMap.lock` (non bloquant) au lieu d'un
  `EntryProcessor` ; quota évalué sous le verrou.
- Suppression de `SessionContext`, `sizeProfile` inliné dans `UploadSession`.
- Logs `INFO` terses au format `clé=valeur`, exploitables par Splunk.
- SLO de `televerse.session.final.bytes` dérivés de la configuration ; métriques
  Prometheus restreintes aux séries applicatives, `/actuator` exclu.

### Corrigé

- Orientation EXIF des photos de téléphone respectée avant conversion en PDF.
- Collision de `pageId` sur téléversements concurrents.
- Rejet des PDF excédant `maxPages` avant la recompression Ghostscript.
- Les éditions de page ne réduisent plus le contenu à l'intérieur de la page A4.
- Clé S3 sans préfixe `userId`/`formId`.
- Suffixe « (p.N) » sur le nom des pages issues d'un PDF multi-pages.
- DocAligner n'est plus désactivé silencieusement en production.
- Dépendance OpenBLAS d'OpenCV pour Linux ; `JAVA_TOOL_OPTIONS` pour l'image UBI10.

## [0.2.4] — 2026-03-17

### Ajouté

- Génération de vignettes pour les pages téléversées.

### Modifié

- Stockage individuel de chaque page et fusion différée à la finalisation, en
  remplacement de la fusion incrémentale.

## [0.2.3] — 2026-02-23

### Corrigé

- Encodage du nom de fichier dans la configuration S3 ; configuration du bucket.

## [0.2.2] — 2026-02-18

### Corrigé

- Sérialisation JSON des objets stockés dans Hazelcast.
- Chemin de base des contrôleurs et configuration CORS.
- URL de base sur le pod de développement.

## [0.2.1] — 2026-02-17

### Corrigé

- Configuration CORS, y compris dans le profil `dev`.

## [0.2.0] — 2026-02-17

### Ajouté

- Gestion de la finalisation du téléversement.
- Chemin de base REST fixé à `/api` (l'application n'est pas exposée via la gateway).

## [0.1.0] — 2026-02-16

### Ajouté

- Version initiale : création de session, téléversement d'images, conversion en PDF,
  stockage S3 et notifications SSE.
- Licence Apache 2.0 et README.

[Non publié]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.9...HEAD
[1.0.9]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.5...v1.0.6
[1.0.5]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.17...v1.0.0
[0.2.17]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.16...v0.2.17
[0.2.16]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.15...v0.2.16
[0.2.15]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.14...v0.2.15
[0.2.14]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.13...v0.2.14
[0.2.13]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.12...v0.2.13
[0.2.12]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.11...v0.2.12
[0.2.11]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.10...v0.2.11
[0.2.10]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.9...v0.2.10
[0.2.9]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.7...v0.2.8
[0.2.7]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/republique-et-canton-de-geneve/ge-televerse-back/releases/tag/v0.1.0
