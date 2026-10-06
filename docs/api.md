# API GE-Televerse Backend

Documentation de l'API REST du backend GE-Televerse. Tous les endpoints sont sous le préfixe `/api` (configuré via `spring.webflux.base-path`).

## Spécification OpenAPI

La spec OpenAPI 3.1 est auto-générée via springdoc et le plugin Maven `springdoc-openapi-maven-plugin`. Pour régénérer :

```bash
mvn verify -Popenapi-generation -Dopenapi-generation=true -DskipTests
```

Le fichier produit se trouve dans `docs/openapi/openapi-ge-televerse.yaml`. Il est versionné dans le dépôt afin que les changements d'API soient visibles dans les merge requests.

## Vue d'ensemble

Le backend permet aux utilisateurs de scanner des documents depuis leur téléphone et de les assembler en un PDF unique stocké sur S3. Le flux typique :

1. Le SPA desktop crée une session (`POST /api/sessions`) et affiche un QR code
2. Le SPA s'abonne aux événements SSE (`GET /api/sessions/{token}/events`)
3. Le mobile scanne le QR code et signale sa connexion (`POST /api/sessions/{token}/phone-connect`) — le desktop grise son interface
4. Le mobile uploade des photos (`POST /api/upload/{token}`)
5. Le mobile a terminé (`POST /api/sessions/{token}/phone-disconnect`) — le desktop réactive son interface
6. Le desktop finalise la session (`POST /api/sessions/{token}/finalize`)
7. Le `documentId` est transmis au web component via `postMessage`, puis à l'application hôte
8. L'application hôte récupère le PDF via `GET /api/documents/{documentId}` avec un JWT OIDC (audience `AEL.TELEVERSE`) — consommation unique, suppression S3 immédiate

L'étape 3-5 peut se répéter (l'utilisateur peut aller et venir entre desktop et mobile).

---

## Sessions

### POST /api/sessions

Crée une nouvelle session de scan.

**Corps de la requête :**

| Champ | Type | Requis | Description |
|-------|------|--------|-------------|
| `sizeProfile` | string | non | **Ignoré.** Le profil de taille est dérivé côté serveur du header `Origin` (whitelist `app.session-create.profiles`, défaut `S`). La valeur du corps n'est jamais consultée — un usager pourrait l'éditer (attribut DOM, rejeu de requête) et s'auto-augmenter son quota. Champ conservé pour compatibilité ascendante. |

Le corps entier est optionnel. Une session est isolée par le token UUID renvoyé — chaque clic du host génère une nouvelle session, et la précédente est implicitement abandonnée.

**Headers :**

| Header | Requis | Description |
|--------|--------|-------------|
| `Origin` | oui | Origine RÉELLE de l'appelant (le webcomponent `<ge-televerse>`, sur la page hôte). **Source de confiance qui gouverne le profil de taille** (cf. corps). Posé d'office par les navigateurs sur tout `POST`, infalsifiable en JS ; un appel sans `Origin` (curl, server-to-server) renvoie `400`. CORS + le verrou same-origin n'autorisent la création cross-origin que depuis le realm configuré (`app.session-create.allowed-origin-suffixes`, ex. `.ge.ch`). |
| `Host` | oui | Hôte qui sert la SPA/API — utilisé pour construire `uploadUrl` (scheme `https` + `Host`). |

**Réponse 200 :**

```json
{
  "token": "a1b2c3d4-...",
  "uploadUrl": "https://host/scan/a1b2c3d4-...",
  "expiresAt": 1713693600000,
  "sizeProfile": "S",
  "maxTotalBytes": 26214400,
  "maxPages": 100
}
```

Le `uploadUrl` est construit comme `https://<Host>/scan/<token>` — l'hôte qui sert l'API sert aussi la SPA et le mobile en production. On NE l'infère plus de l'`Origin` : depuis que le webcomponent crée la session depuis la page hôte, l'`Origin` est celui du formulaire (cross-origin), pas celui de la SPA.

### GET /api/sessions/{token}

Récupère l'état actuel d'une session.

**Réponse 200 :**

```json
{
  "session": {
    "token": "a1b2c3d4-...",
    "documentId": "a1b2c3d4-....pdf",
    "sizeProfile": "S",
    "createdAt": "2026-04-21T06:00:00Z",
    "expiresAt": "2026-04-21T06:15:00Z",
    "pages": [],
    "pageCounter": 0,
    "phoneConnected": false
  },
  "sizeProfile": "S",
  "totalBytes": 0,
  "maxTotalBytes": 26214400,
  "maxPages": 100
}
```

### GET /api/sessions/{token}/events

Flux Server-Sent Events (SSE) pour recevoir les notifications en temps réel.

**Produces :** `text/event-stream`

**Types d'événements :**

| Type | Déclencheur | Champs clés |
|------|-------------|-------------|
| `upload-complete` | Page uploadée | `fileName`, `contentType`, `fileSize`, `pageId`, `pageIndex`, `totalPages`, `totalBytes`, `maxTotalBytes` |
| `page-deleted` | Page supprimée | `pageId`, `totalPages`, `totalBytes`, `maxTotalBytes` |
| `pages-reordered` | Pages réordonnées | `totalPages`, `totalBytes`, `maxTotalBytes` |
| `phone-connected` | Téléphone connecté (QR scanné) | `documentId` |
| `phone-disconnected` | Téléphone terminé (bouton "Terminé") | `documentId` |
| `session-complete` | Session finalisée | `documentId`, `totalPages` |

### POST /api/sessions/{token}/finalize

Fusionne toutes les pages en un PDF unique sur S3 et clôt la session.

La session passe d'abord en `FINALIZING` : jusqu'à la fin de la fusion, toute
mutation de contenu (upload, édition, suppression, réordonnancement) répond `409`
`SESSION_FINALIZING` — la liste de pages fusionnée est celle de cet instant. Une fois
le PDF composite écrit, la session passe en `FINALIZED` et ses pages sont purgées.
Une fusion en échec, hors délai ou abandonnée par le client (déconnexion) rouvre la
session ; celle d'un pod mort la libère à l'échéance du bail
(`app.finalize-timeout` + 30 s).

**Idempotente** : rejouée sur une session déjà finalisée (réponse perdue, retry),
elle renvoie `200` et le même `documentId` sans refusionner.

**Réponse 200 :**

```json
{
  "documentId": "a1b2c3d4-....pdf",
  "message": "Session finalisée"
}
```

**Erreurs :**
- `400` — Session invalide ou expirée, ou aucune page à fusionner
- `429` `FINALIZE_BUSY` — Admission control des finalisations saturé (bulkhead `finalize`, séparé des aperçus), **ou** une autre finalisation de la même session est en cours. Retryable après `Retry-After` : le rejeu obtient le `documentId` une fois la première terminée.
- `504 Gateway Timeout` — la fusion a dépassé le timeout configuré (`app.finalize-timeout`, défaut 2 min). La session est rouverte. **Le timeout du client doit dépasser cette valeur**, faute de quoi il abandonne une fusion encore en cours.

### GET /api/sessions/{token}/preview.pdf

Aperçu du PDF composite avant finalisation. Fusionne les pages courantes en mémoire et renvoie le résultat. **Idempotent** : peut être rappelé pendant que de nouvelles pages arrivent. Ne touche pas S3 et ne supprime aucune page.

**Produces :** `application/pdf`

**Headers :**

| Header | Valeur |
|--------|--------|
| `Content-Type` | `application/pdf` |
| `Content-Disposition` | `inline; filename="preview.pdf"` |
| `Cache-Control` | `no-cache, private` |

**Erreurs :**
- `404 Not Found` — session expirée/inexistante OU aucune page dans la session
- `429` `MERGE_BUSY` — Admission control des assemblages saturé (bulkhead `merge`). Retryable après `Retry-After`.

### POST /api/sessions/{token}/keep-alive

Prolonge la session sans autre action. Utilisé par le SPA desktop quand l'utilisateur clique sur "Rester connecté" avant l'expiration du timeout.

**Réponse 200 :**

```json
{
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée

### POST /api/sessions/{token}/phone-connect

Signale que le téléphone s'est connecté à la session (QR code scanné, page d'upload ouverte). Le SPA desktop reçoit un événement SSE `phone-connected` pour griser son interface. Renouvelle le TTL.

**Réponse 200 :**

```json
{
  "message": "Phone connected",
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée

### POST /api/sessions/{token}/phone-disconnect

Signale que le téléphone a terminé (bouton "Terminé"). Le SPA desktop reçoit un événement SSE `phone-disconnected` pour réactiver son interface. Renouvelle le TTL.

**Réponse 200 :**

```json
{
  "message": "Phone disconnected",
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée

### POST /api/sessions/{token}/pages/{pageId}/deskew

Applique la correction de perspective automatique sur une page existante. Détecte le document dans l'image et le redresse en rectangle. Si la détection échoue, l'image reste inchangée.

**Réponse 200 :**

```json
{
  "message": "Correction de perspective appliquée",
  "applied": true,
  "totalBytes": 512000,
  "maxTotalBytes": 26214400,
  "expiresAt": 1713694500000
}
```

| Champ | Description |
|-------|-------------|
| `applied` | `true` si le document a été détecté et redressé, `false` sinon |
| `totalBytes` | Taille cumulée de la session après correction (peut avoir changé) |
| `expiresAt` | Nouvelle expiration de la session (epoch ms) — la mutation renouvelle le TTL |
| `message` | Description textuelle du résultat |

**Pipeline de détection (cascade tolérante) :**

1. **Stage strict** — Canny + dilate + `approxPolyDP` à 4 sommets. Cas idéal (document propre, fond uni).
2. **Stage souple** — `MORPH_CLOSE` à plusieurs tailles de noyau (0,5 % à 5 % de min(w,h)) pour combler les espaces inter-articles sur photos de magazine. Multi-epsilon `approxPolyDP`, retombée sur l'enveloppe convexe si nécessaire.
3. **Stage `minAreaRect`** — repli quad rectangulaire sur le plus grand contour substantiel.

Score = `(quadArea / imageArea) × rectangularity`. Le meilleur candidat global gagne, sous réserve d'un score minimum (0,15) et d'une marge minimale par rapport aux bords (0,5 %).

Si tous les étages échouent, l'image reste inchangée et la réponse renvoie `applied: false`.

**Erreurs :**
- `400` — Session invalide ou expirée
- `404` — Page introuvable dans la session
- `409` `PAGE_GONE` · `PAGE_CHANGED` · `SESSION_FINALIZING` · `SESSION_FINALIZED` — cf. [Conflits de session](#conflits-de-session)
- `413` `SESSION_SIZE_EXCEEDED` — la nouvelle version de la page ferait dépasser `maxTotalBytes` ; la page reste inchangée
- `422` `PAGE_NOT_EDITABLE` — la page ne se réduit pas à une image (page de PDF portant du texte, des tracés ou plusieurs images) : la reconstruire depuis son image effacerait le reste. Rotation et suppression restent possibles.
- `429` `EDIT_BUSY` — Admission control des éditions saturé (bulkhead `edit`). Retryable après `Retry-After`. Même sémantique que `UPLOAD_BUSY` côté client.

### POST /api/sessions/{token}/pages/{pageId}/crop

Recadre automatiquement une page sur le document détecté. Utilise le même pipeline de détection en cascade que le deskew, mais applique un simple recadrage (`boundingRect`) au lieu d'une transformation perspective. Conserve les pixels originaux sans resampling. Si la détection échoue, l'image reste inchangée.

**Réponse 200 :**

```json
{
  "message": "Recadrage appliqué",
  "applied": true,
  "totalBytes": 412000,
  "maxTotalBytes": 26214400,
  "expiresAt": 1713694500000
}
```

| Champ | Description |
|-------|-------------|
| `applied` | `true` si le document a été détecté et recadré, `false` sinon |
| `expiresAt` | Nouvelle expiration de la session (epoch ms) |

**Erreurs :**
- `400` — Session invalide ou expirée
- `404` — Page introuvable dans la session
- `409` `PAGE_GONE` · `PAGE_CHANGED` · `SESSION_FINALIZING` · `SESSION_FINALIZED` — cf. [Conflits de session](#conflits-de-session)
- `413` `SESSION_SIZE_EXCEEDED` — la nouvelle version de la page ferait dépasser `maxTotalBytes` ; la page reste inchangée
- `422` `PAGE_NOT_EDITABLE` — la page ne se réduit pas à une image (page de PDF portant du texte, des tracés ou plusieurs images) : la reconstruire depuis son image effacerait le reste. Rotation et suppression restent possibles.
- `429` `EDIT_BUSY` — Admission control des éditions saturé (bulkhead `edit`). Retryable après `Retry-After`. Même sémantique que `UPLOAD_BUSY` côté client.

### POST /api/sessions/{token}/pages/{pageId}/white-balance

Applique la correction de balance des blancs (OpenCV SimpleWB) sur une page existante. Neutralise les dominantes colorées dues à l'éclairage. La géométrie de la page est préservée : seuls les pixels de l'image embarquée sont modifiés (pas de re-render PDF→image→PDF qui rétrécirait progressivement le contenu).

**Réponse 200 :**

```json
{
  "message": "Balance des blancs appliquée",
  "totalBytes": 512000,
  "maxTotalBytes": 26214400,
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée
- `404` — Page introuvable dans la session
- `409` `PAGE_GONE` · `PAGE_CHANGED` · `SESSION_FINALIZING` · `SESSION_FINALIZED` — cf. [Conflits de session](#conflits-de-session)
- `413` `SESSION_SIZE_EXCEEDED` — la nouvelle version de la page ferait dépasser `maxTotalBytes` ; la page reste inchangée
- `422` `PAGE_NOT_EDITABLE` — la page ne se réduit pas à une image (page de PDF portant du texte, des tracés ou plusieurs images) : la reconstruire depuis son image effacerait le reste. Rotation et suppression restent possibles.
- `429` `EDIT_BUSY` — Admission control des éditions saturé (bulkhead `edit`). Retryable après `Retry-After`. Même sémantique que `UPLOAD_BUSY` côté client.

### POST /api/sessions/{token}/pages/{pageId}/rotate

Applique une rotation de 90° sur une page existante via la métadonnée `/Rotate` du PDF (lossless, dimensions de page inchangées). La vignette est régénérée pour refléter la nouvelle orientation.

**Paramètres :**

| Paramètre | In | Type | Requis | Description |
|-----------|-----|------|--------|-------------|
| `direction` | query | string | non | `cw` (horaire, défaut) ou `ccw` (anti-horaire) |

**Réponse 200 :**

```json
{
  "message": "Page tournée",
  "totalBytes": 512000,
  "maxTotalBytes": 26214400,
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée
- `404` — Page introuvable dans la session
- `409` `PAGE_GONE` · `PAGE_CHANGED` · `SESSION_FINALIZING` · `SESSION_FINALIZED` — cf. [Conflits de session](#conflits-de-session)
- `413` `SESSION_SIZE_EXCEEDED` — la nouvelle version de la page ferait dépasser `maxTotalBytes` ; la page reste inchangée
- `429` `EDIT_BUSY` — Admission control des éditions saturé (bulkhead `edit`). Retryable après `Retry-After`. Même sémantique que `UPLOAD_BUSY` côté client.

---

### DELETE /api/sessions/{token}/pages/{pageId}

Supprime une page de la session, puis purge ses objets (PDF sur S3, vignette dans le
cache) — versions d'undo comprises.

**Réponse 200 :**

```json
{
  "message": "Page supprimée",
  "totalPages": 2,
  "totalBytes": 512000,
  "maxTotalBytes": 26214400,
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée
- `404` — Page introuvable dans la session
- `409` `SESSION_FINALIZING` · `SESSION_FINALIZED` — cf. [Conflits de session](#conflits-de-session)

### PUT /api/sessions/{token}/pages/order

Réordonne les pages de la session.

**Corps de la requête :**

```json
{
  "pageIds": ["page-003", "page-001", "page-002"]
}
```

La liste doit contenir exactement les mêmes `pageId` que la session courante, chacun
une fois. Elle est contrôlée deux fois : à la réception (`400` si elle ne correspond
pas à la session lue) et sous le verrou de session (`409` `PAGES_CHANGED` si une page
a été ajoutée ou supprimée entre-temps).

**Réponse 200 :**

```json
{
  "message": "Pages réordonnées",
  "totalPages": 3,
  "expiresAt": 1713694500000
}
```

**Erreurs :**
- `400` — Session invalide ou expirée, `pageIds` absent, ou liste ne correspondant pas aux pages de la session
- `409` `PAGES_CHANGED` · `SESSION_FINALIZING` · `SESSION_FINALIZED` — cf. [Conflits de session](#conflits-de-session)

### Conflits de session

Les mutations sont contrôlées sous le verrou de session, sur l'état qui fait foi : un
conflit n'écrit rien, et ne se résout pas en rejouant la même requête. Le client
recharge la session (`GET /api/sessions/{token}`) puis, le cas échéant, laisse
l'usager recommencer. Corps : `{"error": "<code>", "message": "…"}`.

| Code HTTP | `error`               | Condition |
|-----------|-----------------------|-----------|
| `409`     | `PAGE_GONE`           | La page a été supprimée pendant l'édition. |
| `409`     | `PAGE_CHANGED`        | La page a été modifiée (autre édition, undo, autre appareil) depuis sa lecture ; l'édition partait d'une version supplantée. |
| `409`     | `PAGES_CHANGED`       | Réordonnancement : la liste ne correspond plus aux pages de la session. |
| `409`     | `SESSION_FINALIZING`  | Finalisation en cours : le contenu de la session est figé. |
| `409`     | `SESSION_FINALIZED`   | Session déjà finalisée. |

### GET /api/sessions/{token}/pages/{pageId}/thumbnail

Récupère un aperçu JPEG d'une page. Deux modes selon le paramètre `width` :

**Paramètres :**

| Paramètre | In | Type | Requis | Description |
|-----------|-----|------|--------|-------------|
| `width` | query | integer | non | Largeur en pixels. Si absent, sert la vignette pré-stockée (120px). |

**Sans `width`** — sert la vignette pré-stockée sur S3 (rapide, 120px par défaut).
- **Cache-Control :** `max-age=300, private`

**Avec `width`** (ex. `?width=800`) — génère un JPEG à la volée depuis le PDF de la page. Utilisé pour le popover d'édition pleine page. Toujours à jour après une édition (rotation, crop, etc.).
- **Cache-Control :** `no-cache, private`

**Produces :** `image/jpeg`
**Erreurs :**
- `404 Not Found` — la page ou la vignette n'existe pas
- `429` `MERGE_BUSY` — **uniquement en mode rendu à la volée** (`width`/`preview`) : admission control des assemblages saturé (bulkhead `merge`). Retryable après `Retry-After`. Le mode vignette pré-stockée (sans `width`) n'est pas borné.

---

## Upload

### POST /api/upload/{token}

Uploade un fichier (image ou PDF) dans une session existante.

**Comportement multi-pages :** si le fichier est un PDF multi-pages, chaque page est extraite et stockée individuellement avec sa propre vignette. Chaque page est indépendamment réordonnable, supprimable et éditable (rotation, balance des blancs, correction de perspective). Un événement SSE `upload-complete` est émis pour chaque page extraite.

**Paramètres :**

| Paramètre | In | Type | Requis | Description |
|-----------|-----|------|--------|-------------|
| `token` | path | string | oui | Token de session |
| `file` | body (multipart) | file | oui | Fichier à uploader (image JPEG/PNG ou PDF) |

**Réponse 200 :**

```json
{
  "documentId": "a1b2c3d4-....pdf",
  "message": "Upload successful",
  "expiresAt": 1713694500000,
  "pageId": "page-003",
  "pageIndex": 2,
  "totalPages": 3,
  "thumbnailUrl": "/api/sessions/a1b2c3d4-.../pages/page-003/thumbnail?v=1713694500000",
  "sizeProfile": "S",
  "totalBytes": 245000,
  "maxTotalBytes": 26214400,
  "pagesAdded": 3,
  "errorCode": null
}
```

| Champ | Description |
|-------|-------------|
| `pageId` | ID de la dernière page ajoutée |
| `pagesAdded` | Nombre de pages créées (1 pour une image, N pour un PDF multi-pages) |
| `totalPages` | Nombre total de pages dans la session après l'upload |
| `thumbnailUrl` | URL de la vignette avec un cache-buster `?v={epochMillis}` aligné sur `lastModifiedAt` de la page |
| `errorCode` | `null` en cas de succès complet, code de rejet sinon (cf. tableau ci-dessous) |

**Upload partiel (200) :** sous concurrence (multi-sélection, second appareil), un PDF multi-pages peut être accepté en partie : les pages qui tiennent dans les limites sont attachées, le reste est refusé sous le verrou et rollback de S3. La réponse reste `200` avec `pagesAdded < nombre de pages du PDF` et `errorCode` portant le motif du refus : `PDF_TOO_MANY_PAGES` (limite de pages) ou `SESSION_SIZE_EXCEEDED` (quota d'octets).

**Erreurs :**

Deux formes de corps selon l'origine du rejet : les réponses construites par `UploadController` (415, 429 limite de pages, 200 partiel) portent le discriminateur dans `errorCode` ; celles de `GlobalExceptionHandler` (400 `INVALID_MULTIPART`, 413, 422, 429 `UPLOAD_BUSY`) le portent dans `error`. Le front (`useUpload.js`) lit `error ?? errorCode`.

| Code HTTP | Discriminateur         | Champ       | Condition                                                                |
|-----------|------------------------|-------------|--------------------------------------------------------------------------|
| `400`     | (absent)               | —           | Session invalide — token expiré ou inexistant                            |
| `400`     | `INVALID_MULTIPART`    | `error`     | Corps multipart refusé par le codec : plus d'une part, ou flux mal formé. Aucune session n'est consultée. |
| `409`     | `SESSION_FINALIZING` / `SESSION_FINALIZED` | `error` | Session en cours de finalisation, ou finalisée : plus aucune page n'y est acceptée ; un fichier déjà stocké pour cet envoi est supprimé. |
| `413`     | `FILE_TOO_LARGE`       | `error`     | Fichier au-delà de `app.limits.multipart-ceiling` (50 Mo), refusé par le codec avant tout traitement ; l'écriture sur disque s'arrête au plafond. |
| `413`     | `SESSION_SIZE_EXCEEDED`| `error`     | La page ou le cumul d'un PDF multi-pages ferait dépasser `maxTotalBytes`. Rollback S3 automatique. |
| `415`     | `INVALID_FILE_FORMAT`  | `errorCode` | Extension hors `app.upload.allowed-formats`                              |
| `422`     | `CORRUPT_FILE`         | `error`     | Fichier illisible / endommagé : ni PDF ni image décodable (PDFBox / ImageIO). Non-retryable — l'utilisateur doit choisir un autre fichier. |
| `422`     | `INFECTED_FILE`        | `error`     | Fichier rejeté par le scan antivirus (clamd). Non-retryable.             |
| `429`     | `PAGE_LIMIT_REACHED`   | `errorCode` | `maxPages` atteint                                                       |
| `429`     | `PDF_TOO_MANY_PAGES`   | `errorCode` | Le PDF contient plus de pages que les slots restants (pré-contrôle, ou refus sous verrou avec aucune page attachée) |
| `429`     | `UPLOAD_BUSY`          | `error`     | Admission control saturé (bulkhead) — retryable après `Retry-After`      |

---

## Documents (proxy OIDC)

Endpoint dédié aux **applications hôtes** (formulaires métier) pour récupérer le PDF composite finalisé. Pas appelé par le SPA — celui-ci a son propre flux via `preview.pdf` puis transmission du `documentId` au web component via `postMessage`.

### GET /api/documents/{documentId}

Streame le PDF final et le supprime de S3 dès la réponse écrite avec succès (consommation unique).

**Authentification :** Bearer JWT OIDC. La signature est validée contre le JWKS configuré (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`), l'issuer contre `issuer-uri`, et le claim `aud` doit contenir la valeur de `app.security.jwt-audience` (par défaut `AEL.TELEVERSE`).

**Paramètres :**

| Paramètre | In | Type | Requis | Description |
|-----------|-----|------|--------|-------------|
| `documentId` | path | string | oui | Identifiant du PDF reçu via l'événement SSE `session-complete`. Format strict `<uuid>.pdf` — tout autre format est rejeté en `400`. |
| `Authorization` | header | string | oui | `Bearer <jwt>` |

**Produces :** `application/pdf`

**Headers de réponse :**

| Header | Valeur |
|--------|--------|
| `Content-Type` | `application/pdf` |
| `Content-Disposition` | `attachment; filename="document.pdf"` |
| `Content-Length` | taille du PDF en octets |

**Codes de retour :**

| Code HTTP | Condition |
|-----------|-----------|
| `200` | PDF streamé, suppression S3 effectuée immédiatement après l'écriture de la réponse |
| `400` | `documentId` ne respecte pas le format `<uuid>.pdf` (protection contre la lecture d'une clé S3 arbitraire via le proxy) |
| `401` | JWT absent, mal signé, expiré, issuer invalide ou audience absente |
| `410` | Document déjà consommé ou inexistant sur S3 |

**Consommation unique :** un GET réussi supprime immédiatement le PDF sur S3. Un second appel renvoie `410 Gone`. Si la réponse échoue (déconnexion client mid-stream), la suppression n'a pas lieu et un nouveau GET peut réussir.

**CORS :** route ouverte à toutes les origines (la frontière de sécurité est le JWT). Les autres routes restent restreintes par `app.cors.allowed-origins`.

---

## Profils de taille

| Profil | Taille max session | Cas d'usage |
|--------|-------------------|-------------|
| `S` | 25 Mo | Pièce d'identité, justificatif court |
| `M` | 100 Mo | Dossier standard multi-pièces |
| `L` | 250 Mo | Dossier volumineux (permis de construire) |
| `XL` | 500 Mo | Usages exceptionnels |

Un upload multipart unique ne peut pas dépasser **100 Mo** (plafond infrastructure), indépendamment du profil. Les sessions L/XL se construisent en empilant plusieurs uploads.

---

## Traitement d'image

### À l'upload

Le backend applique les traitements suivants sur les fichiers uploadés :

- **Images** (JPEG, PNG) : redimensionnées à max 1754px (A4 @ 150 DPI), compressées JPEG 75%, centrées sur page A4 avec marges 0.5 pouce, vignette JPEG 120px générée.
- **PDF mono-page** : stocké tel quel avec génération de vignette.
- **PDF multi-pages** : découpé en pages individuelles (PDFBox Splitter), chaque page stockée séparément avec sa propre vignette. Chaque page est ensuite indépendamment éditable, réordonnable et supprimable.

### Post-upload (à la demande)

Les traitements suivants sont déclenchés par l'utilisateur via un bouton dans l'interface, sur une page déjà uploadée. Le PDF et la vignette sont recréés sur S3.

- **Balance des blancs** (`POST .../white-balance`) — neutralise les dominantes colorées (éclairage tungstène, néon) via OpenCV SimpleWB. Toujours appliquée (pas de détection préalable).
- **Correction de perspective** (`POST .../deskew`) — détecte automatiquement le document dans l'image et le redresse en rectangle via OpenCV (Canny + findContours + warpPerspective). Si la détection échoue, la page reste inchangée (`applied: false`).
- **Recadrage automatique** (`POST .../crop`) — détecte le document et recadre sur son rectangle englobant (boundingRect). Conserve les pixels originaux, pas de resampling. Complémentaire au deskew.
- **Rotation** (`POST .../rotate?direction=cw|ccw`) — rotation de 90° horaire ou anti-horaire via Java 2D. Pas de dépendance OpenCV.

---

## Configuration

Propriétés applicatives sous le préfixe `app.*` :

| Propriété | Défaut | Description |
|-----------|--------|-------------|
| `app.session-ttl` | `PT15M` | Durée de vie d'une session (renouvelée à chaque upload) |
| `app.cleanup-interval` | `PT1M` | Intervalle de nettoyage des sessions expirées (ISO-8601) |
| `app.sse-heartbeat-interval` | `PT15S` | Intervalle des heartbeats SSE (ISO-8601) |
| `app.finalize-timeout` | `PT2M` | Timeout pour la fusion PDF |
| `app.thumbnail-width` | `120` | Largeur des vignettes en pixels |
| `app.limits.max-pages` | `100` | Nombre max de pages par session |
| `app.limits.multipart-ceiling` | `50MB` | Taille max d'un fichier par upload, appliquée par le codec via `spring.webflux.multipart.max-disk-usage-per-part` |
| `app.limits.profiles.{S,M,L,XL}.max-total-size` | variable | Taille max par profil |
| `app.security.enabled` | `true` | Active la chaîne Spring Security (JWT sur `/api/documents/**`). `false` en dev. |
| `app.security.jwt-audience` | `AEL.TELEVERSE` | Audience attendue dans le claim `aud` du JWT |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | — | URL du JWKS (env `JWK_SET_URI`) |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | — | Issuer attendu (env `ISSUER_URI`) |
