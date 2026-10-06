package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.AwsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
@Slf4j(topic = "pdfprocessing")
public class StorageService {

    private final S3AsyncClient s3Client;
    private final String bucket;
    private final PdfService pdfService;
    private final PerspectiveCorrectionService perspectiveCorrectionService;
    private final WhiteBalanceService whiteBalanceService;
    private final ThumbnailCacheService thumbnailCache;

    public StorageService(S3AsyncClient s3Client, AwsProperties properties, PdfService pdfService,
                          PerspectiveCorrectionService perspectiveCorrectionService,
                          WhiteBalanceService whiteBalanceService,
                          ThumbnailCacheService thumbnailCache) {
        this.s3Client = s3Client;
        this.bucket = properties.s3().bucket();
        this.pdfService = pdfService;
        this.perspectiveCorrectionService = perspectiveCorrectionService;
        this.whiteBalanceService = whiteBalanceService;
        this.thumbnailCache = thumbnailCache;
    }

    /**
     * Résultat du stockage d'une page avec sa vignette.
     *
     * @param pageKey      la clé S3 du PDF de la page
     * @param thumbnailKey la clé de la vignette dans le cache Hazelcast, ou null
     *                     si la génération a échoué
     * @param storedSize   nombre d'octets effectivement écrits sur S3 pour le PDF
     */
    public record StorePageResult(String pageKey, String thumbnailKey, long storedSize) {}

    /**
     * Convertit le fichier uploadé en PDF et génère une vignette JPEG en parallèle,
     * puis stocke le PDF sur S3 et la vignette dans le cache Hazelcast.
     * Si la génération de la vignette échoue, l'upload continue normalement (fallback gracieux).
     *
     * @param pageKey      la clé S3 de la page individuelle
     * @param thumbnailKey la clé de la vignette dans le cache
     * @param filePart     le fichier uploadé
     * @return le résultat avec pageKey et thumbnailKey (nullable)
     */
    public Mono<StorePageResult> storePageWithThumbnail(String pageKey, String thumbnailKey, FilePart filePart) {
        return bufferFilePart(filePart)
                .flatMap(uploadBytes -> {
                    // Conversion PDF et génération vignette en parallèle sur boundedElastic.
                    // Le type source (PDF vs image) est détecté par magic bytes dans
                    // PdfService — le Content-Type déclaré par le client n'est pas lu.
                    var pdfMono = Mono.fromCallable(() -> pdfService.convertToPdf(uploadBytes))
                            .subscribeOn(Schedulers.boundedElastic());

                    var thumbMono = Mono.fromCallable(() -> pdfService.generateThumbnail(uploadBytes))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                log.warn("Thumbnail generation failed key={} error={}", pageKey, e.getMessage());
                                return Mono.empty();
                            });

                    return Mono.zip(pdfMono, thumbMono.defaultIfEmpty(new byte[0]))
                            .flatMap(tuple -> {
                                var pdfBytes = tuple.getT1();
                                var thumbBytes = tuple.getT2();
                                var pdfSize = (long) pdfBytes.length;

                                var uploadPdf = uploadToS3(pageKey, pdfBytes, filePart.filename());

                                if (thumbBytes.length > 0) {
                                    // La mise en cache est best-effort : la clé reste valide
                                    // même si l'écriture Hazelcast échoue, puisque la lecture
                                    // régénère la vignette depuis le PDF de la page.
                                    return uploadPdf
                                            .flatMap(key -> thumbnailCache.put(thumbnailKey, thumbBytes)
                                                    .thenReturn(new StorePageResult(key, thumbnailKey, pdfSize)));
                                }
                                return uploadPdf.map(key -> new StorePageResult(key, null, pdfSize));
                            });
                });
    }

    /**
     * Stocke des bytes PDF déjà prêts (issus d'un split multi-pages) avec génération de vignette.
     *
     * @param pageKey        la clé S3 de la page
     * @param thumbnailKey   la clé S3 de la vignette
     * @param pdfBytes       les bytes du PDF (une seule page)
     * @param originalFilename le nom du fichier original
     * @return le résultat avec pageKey, thumbnailKey et taille
     */
    public Mono<StorePageResult> storePageFromBytes(String pageKey, String thumbnailKey,
                                                     byte[] pdfBytes, String originalFilename) {
        return Mono.fromCallable(() -> pdfService.generateThumbnail(pdfBytes))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Thumbnail generation failed key={} error={}", pageKey, e.getMessage());
                    return Mono.empty();
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(thumbBytes -> {
                    var pdfSize = (long) pdfBytes.length;
                    var uploadPdf = uploadToS3(pageKey, pdfBytes, originalFilename);

                    if (thumbBytes.length > 0) {
                        return uploadPdf
                                .flatMap(key -> thumbnailCache.put(thumbnailKey, thumbBytes)
                                        .thenReturn(new StorePageResult(key, thumbnailKey, pdfSize)));
                    }
                    return uploadPdf.map(key -> new StorePageResult(key, null, pdfSize));
                });
    }

    /**
     * Convertit le fichier uploadé en PDF et le stocke individuellement sur S3.
     *
     * @param pageKey  la clé S3 de la page individuelle (ex: token/page-001.pdf)
     * @param filePart le fichier uploadé
     * @return la clé S3
     */
    public Mono<String> storePage(String pageKey, FilePart filePart) {
        return bufferFilePart(filePart)
                .flatMap(uploadBytes -> Mono.fromCallable(() -> pdfService.convertToPdf(uploadBytes))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(pdfBytes -> uploadToS3(pageKey, pdfBytes, filePart.filename()));
    }

    /**
     * Résultat du retraitement (deskew/crop) d'une page existante.
     *
     * @param newSize nombre d'octets du PDF de la nouvelle version (taille actuelle si
     *                la correction n'a pas été appliquée)
     * @param applied true si la correction a été appliquée — une nouvelle version a
     *                alors été écrite sous les clés neuves demandées
     */
    public record DeskewResult(long newSize, boolean applied) {}

    /** Nouvelle version calculée : PDF et vignette (null si sa génération a échoué). */
    private record Version(byte[] pdf, byte[] thumbnail) {}

    /** Travail d'édition exécuté hors event loop, avec le relevé d'étapes de la requête. */
    @FunctionalInterface
    private interface TracedWork<T> {
        T run(EditTrace trace) throws Exception;
    }

    /*
     * Toutes les éditions ci-dessous écrivent leur résultat sous des clés NEUVES
     * (newPageKey / newThumbnailKey) et ne touchent jamais la version courante : c'est
     * la session, sous verrou, qui adopte ou non la nouvelle version
     * (cf. PageInfo.withNewVersion, SessionService.commitPageEdit). Tant qu'elle ne
     * l'a pas adoptée, cette version n'existe pour personne, et l'appelant la supprime
     * s'il y renonce. Les versions précédente et initiale restent sur leurs propres
     * clés : il n'y a plus de sauvegarde à écrire avant l'édition, ni de copie à faire
     * pour l'annuler.
     */

    /**
     * Recadre une page sur le document détecté (bounding rectangle). Travaille sur
     * l'image embarquée (pas le rendu A4 avec marges) pour ne pas rétrécir le contenu
     * à chaque passe. Rien n'est écrit si aucun document n'est détecté.
     *
     * @param pageKey         clé S3 de la version courante (lue, jamais écrite)
     * @param newPageKey      clé S3 de la nouvelle version
     * @param newThumbnailKey clé de cache de la vignette de la nouvelle version
     */
    public Mono<DeskewResult> cropPage(String pageKey, String newPageKey, String newThumbnailKey) {
        return rebuildFromImage(pageKey, newPageKey, newThumbnailKey, perspectiveCorrectionService::crop);
    }

    /**
     * Corrige la perspective d'une page (même contrat que {@link #cropPage}).
     */
    public Mono<DeskewResult> deskewPage(String pageKey, String newPageKey, String newThumbnailKey) {
        return rebuildFromImage(pageKey, newPageKey, newThumbnailKey, perspectiveCorrectionService::apply);
    }

    /**
     * Crop / deskew : extrait l'image embarquée, applique la correction, reconstruit
     * la page — rotation {@code /Rotate} de la source reportée, l'image embarquée
     * n'étant jamais tournée (cf. {@link PdfService#imageToPagePdf(BufferedImage, int)}).
     */
    private Mono<DeskewResult> rebuildFromImage(String pageKey, String newPageKey, String newThumbnailKey,
                                                Function<BufferedImage, PerspectiveCorrectionService.Result> correction) {
        return EditTrace.stage("s3.get", fetchFromS3(pageKey))
                .flatMap(pdfBytes -> onWorker(trace -> {
                    var image = trace.time("pdf.extract", () -> pdfService.extractFirstImage(pdfBytes));
                    if (image == null) {
                        return null;
                    }
                    var result = trace.time("detect", () -> correction.apply(image));
                    if (!result.applied()) {
                        return null;
                    }
                    int rotation = pdfService.pageRotation(pdfBytes);
                    var newPdf = trace.time("pdf.build", () -> pdfService.imageToPagePdf(result.image(), rotation));
                    return new Version(newPdf, thumbnailOf(trace, newPdf, newPageKey));
                })
                        .flatMap(version -> writeVersion(newPageKey, newThumbnailKey, version)
                                .thenReturn(new DeskewResult(version.pdf().length, true)))
                        // Rien d'appliqué (pas d'image, pas de document) : rien d'écrit.
                        .defaultIfEmpty(new DeskewResult(pdfBytes.length, false)));
    }

    /**
     * Rotation de 90° d'une page, sans perte ({@code /Rotate}), écrite comme nouvelle
     * version (cf. {@link #cropPage}).
     *
     * @param clockwise true = 90° horaire, false = 90° anti-horaire
     * @return la taille du PDF de la nouvelle version
     */
    public Mono<Long> rotatePage(String pageKey, boolean clockwise, String newPageKey, String newThumbnailKey) {
        return EditTrace.stage("s3.get", fetchFromS3(pageKey))
                .flatMap(pdfBytes -> onWorker(trace -> {
                    var newPdf = trace.time("pdf.rotate", () -> pdfService.rotatePdfPage(pdfBytes, clockwise));
                    return new Version(newPdf, thumbnailOf(trace, newPdf, newPageKey));
                }))
                .flatMap(version -> writeVersion(newPageKey, newThumbnailKey, version)
                        .thenReturn((long) version.pdf().length));
    }

    /**
     * Balance des blancs sur l'image embarquée, écrite comme nouvelle version (cf.
     * {@link #cropPage}). Seuls les pixels de l'image XObject changent : la géométrie
     * de la page (MediaBox, position, échelle, rotation) est préservée.
     *
     * @return la taille du PDF de la nouvelle version
     */
    public Mono<Long> whiteBalancePage(String pageKey, String newPageKey, String newThumbnailKey) {
        return EditTrace.stage("s3.get", fetchFromS3(pageKey))
                .flatMap(pdfBytes -> onWorker(trace -> {
                    var newPdf = trace.time("pdf.transform",
                            () -> pdfService.transformFirstImage(pdfBytes, whiteBalanceService::apply));
                    return new Version(newPdf, thumbnailOf(trace, newPdf, newPageKey));
                }))
                .flatMap(version -> writeVersion(newPageKey, newThumbnailKey, version)
                        .thenReturn((long) version.pdf().length));
    }

    /**
     * Exécute {@code work} sur boundedElastic. Le relevé d'étapes est lu dans le
     * contexte Reactor ici, hors du callable : il n'est pas accessible depuis le
     * thread du worker. Un résultat {@code null} donne un {@link Mono} vide.
     */
    private static <T> Mono<T> onWorker(TracedWork<T> work) {
        return Mono.deferContextual(ctx -> {
            var trace = EditTrace.from(ctx);
            return Mono.fromCallable(() -> work.run(trace)).subscribeOn(Schedulers.boundedElastic());
        });
    }

    /** Vignette d'une nouvelle version ; null si sa génération échoue (non bloquant). */
    private byte[] thumbnailOf(EditTrace trace, byte[] pdf, String pageKey) {
        try {
            return trace.time("thumb", () -> pdfService.generateThumbnail(pdf));
        } catch (Exception e) {
            // Non bloquant : absente du cache, la vignette sera régénérée depuis le PDF
            // à la première lecture.
            log.warn("Thumbnail generation failed key={} error={}", pageKey, e.getMessage());
            return null;
        }
    }

    /** Écrit une nouvelle version : PDF sur S3, vignette dans le cache (si générée). */
    private Mono<Void> writeVersion(String newPageKey, String newThumbnailKey, Version version) {
        var pdf = EditTrace.stage("s3.put", uploadToS3(newPageKey, version.pdf(), null)).then();
        var thumbnail = version.thumbnail() != null
                ? EditTrace.stage("cache.put", thumbnailCache.put(newThumbnailKey, version.thumbnail()))
                : Mono.<Void>empty();
        return Mono.when(pdf, thumbnail);
    }

    /**
     * Génère un aperçu JPEG à la volée depuis un PDF stocké sur S3.
     *
     * @param pageKey la clé S3 du PDF de la page
     * @param width   la largeur souhaitée en pixels
     * @return les bytes JPEG de l'aperçu
     */
    public Mono<byte[]> generatePreview(String pageKey, int width) {
        return fetchFromS3(pageKey)
                .flatMap(pdfBytes -> Mono.fromCallable(
                                () -> pdfService.generateThumbnail(pdfBytes, width))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Régénère une vignette depuis le PDF de la page (défaut de cache) et la
     * réinjecte dans le cache (best-effort), pour que le coût ne soit payé qu'une fois
     * par entrée perdue.
     *
     * <p>Le défaut de cache n'est pas une anomalie — il survient normalement sur
     * expiration du TTL, éviction sous pression mémoire, perte d'un membre du
     * cluster ({@code backup-count: 0}) ou indisponibilité de Hazelcast : le PDF de la
     * page reste la seule source de vérité. Mais chaque régénération coûte un GET S3 du
     * PDF complet et un rendu PDFBox : l'appelant la fait passer par l'admission
     * control (cf. {@code SessionController.getThumbnail}), la lecture du cache, elle,
     * reste libre.</p>
     *
     * @param pageKey      la clé S3 du PDF de la page
     * @param thumbnailKey la clé de cache de la vignette (null → pas de mise en cache)
     * @param width        la largeur de la vignette en pixels
     */
    public Mono<byte[]> regenerateThumbnail(String pageKey, String thumbnailKey, int width) {
        return generatePreview(pageKey, width)
                .flatMap(bytes -> thumbnailCache.put(thumbnailKey, bytes)
                        .thenReturn(bytes));
    }

    public Mono<byte[]> fetchFromS3(String key) {
        var request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return Mono.fromFuture(() -> s3Client.getObject(request, AsyncResponseTransformer.toBytes()))
                .map(ResponseBytes::asByteArray);
    }

    /**
     * Suppressions S3 concurrentes maximales dans un {@link #deleteAllBestEffort}.
     *
     * <p>Sans borne, {@code flatMap} prend sa concurrence par défaut (256) : la purge
     * d'une session de 20 pages éditées émettait jusqu'à 60 DELETE simultanés,
     * multipliés par les pods (purge d'expiration) ou par les finalisations admises
     * (jusqu'à 2000 appels pour un pool de 100 connexions + 200 en attente) — de quoi
     * faire échouer, par « Maximum pending connection acquisitions exceeded », les
     * uploads et finalisations des usagers actifs. Même valeur, même raisonnement que
     * la purge du cache de vignettes ({@code ThumbnailCacheService.PURGE_CONCURRENCY}).</p>
     */
    static final int S3_PURGE_CONCURRENCY = 4;

    /**
     * Supprime un lot de clés S3 en best effort : au plus {@value #S3_PURGE_CONCURRENCY}
     * DELETE en vol, un échec est logué sans interrompre les autres, et le {@link Mono}
     * se termine toujours sans erreur — le cycle de vie du bucket reste le filet ultime.
     * À utiliser pour toute purge (expiration, finalisation) ; les clés nulles et les
     * doublons sont ignorés.
     */
    public Mono<Void> deleteAllBestEffort(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Mono.empty();
        }
        // Filtrage avant fromIterable : Reactive Streams interdit les éléments null.
        var distinct = keys.stream().filter(Objects::nonNull).distinct().toList();
        return Flux.fromIterable(distinct)
                .flatMap(key -> deleteFromS3(key)
                        .onErrorResume(e -> {
                            log.warn("S3 delete failed key={} error={}", key, e.toString());
                            return Mono.empty();
                        }), S3_PURGE_CONCURRENCY)
                .then();
    }

    /**
     * Supprime un objet S3.
     */
    public Mono<Void> deleteFromS3(String key) {
        var request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return Mono.fromFuture(() -> s3Client.deleteObject(request))
                .doOnSuccess(resp -> log.debug("S3 object deleted key={}", key))
                .then();
    }

    /**
     * Résultat de la finalisation d'une session.
     *
     * @param documentId clé S3 du PDF composite final
     * @param bytes      taille en octets du PDF composite (pour métriques / réponse client)
     */
    public record FinalizeOutcome(String documentId, long bytes) {}

    /**
     * Récupère toutes les pages individuelles, les fusionne en un seul PDF et l'écrit
     * sous {@code documentId}. Ne supprime RIEN : la purge des pages revient à
     * l'appelant, une fois la session passée en finalisée (best effort,
     * {@link #deleteAllBestEffort}).
     *
     * <p>Les pages étaient supprimées ici, dans le chemin de succès et sans
     * {@code onErrorResume} : un seul DELETE coupé faisait échouer la finalisation
     * alors que le PDF composite était écrit, et chaque nouvelle tentative échouait
     * ensuite sur les pages déjà supprimées. Écrire le composite est idempotent (même
     * clé) : une finalisation rejouée le réécrit à l'identique.</p>
     *
     * @param pageKeys   les clés S3 des pages dans l'ordre souhaité
     * @param documentId la clé S3 du PDF final
     * @return le documentId et la taille du PDF composite
     */
    public Mono<FinalizeOutcome> mergeAndFinalize(List<String> pageKeys, String documentId) {
        return Flux.fromIterable(pageKeys)
                .concatMap(this::fetchFromS3)
                .collectList()
                .flatMap(pdfBytesList -> Mono.fromCallable(() -> pdfService.mergeMultiplePdfs(pdfBytesList))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(mergedPdf -> uploadToS3(documentId, mergedPdf, null)
                        .map(docId -> new FinalizeOutcome(docId, mergedPdf.length)));
    }

    /**
     * Fusionne les pages courantes en un seul PDF, sans uploader le résultat ni
     * supprimer les pages individuelles. Idempotent : peut être rappelé pendant
     * que de nouvelles pages arrivent. Utilisé par l'aperçu front-end avant
     * finalisation.
     *
     * @param pageKeys les clés S3 des pages dans l'ordre souhaité
     * @return les bytes du PDF fusionné
     */
    public Mono<byte[]> mergePagesPreview(List<String> pageKeys) {
        return Flux.fromIterable(pageKeys)
                .concatMap(this::fetchFromS3)
                .collectList()
                .flatMap(pdfBytesList -> Mono.fromCallable(() -> pdfService.mergeMultiplePdfs(pdfBytesList))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    public Mono<byte[]> bufferFilePart(FilePart filePart) {
        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    var bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                });
    }

    Mono<String> uploadToS3(String key, byte[] pdfBytes, String originalFilename) {
        var encodedFilename = originalFilename != null
                ? URLEncoder.encode(originalFilename, StandardCharsets.UTF_8)
                : "unknown";

        var request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/pdf")
                .contentLength((long) pdfBytes.length)
                .metadata(Map.of("original-filename", encodedFilename))
                .build();

        return Mono.fromFuture(() -> s3Client.putObject(request, AsyncRequestBody.fromBytes(pdfBytes)))
                .doOnSuccess(resp -> log.debug("S3 PDF stored key={} bytes={}", key, pdfBytes.length))
                .thenReturn(key);
    }

}
