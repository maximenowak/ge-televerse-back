# Fixtures de test

## `pdf-transparency-cover.pdf`

Page de couverture (1 page A5) contenant un **groupe de transparence** : une illustration
vectorielle faite de cercles colorés semi-transparents superposés, sur fond blanc.

**Usage :** `GhostscriptServiceTest#recompress_preservesTransparentArtwork`. C'est un
non-régression : avec un preset Ghostscript nu (`/ebook`, `/screen`, `/printer`), la conversion
colorimétrique par défaut aplatit et **efface** l'illustration (sortie ~14 KB au lieu de ~37 KB).
`GhostscriptService` force `-dColorConversionStrategy=/LeaveColorUnchanged` pour l'éviter ; le test
rend la page avant/après recompression et vérifie que les pixels colorés de l'illustration
survivent. Voir la mémoire `ghostscript-ebook-drops-transparency`.

**Provenance :** page 1 (extraite sans re-distillation, via PDFBox) d'un guide public
« COMM Collection N°12 » du SPF Chancellerie (Belgique) — document gouvernemental public, utilisé
uniquement comme entrée de rendu pour ce test.
