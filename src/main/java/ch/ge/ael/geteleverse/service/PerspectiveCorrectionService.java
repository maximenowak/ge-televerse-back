package ch.ge.ael.geteleverse.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.opencv_core.*;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * Service de correction de perspective via OpenCV.
 *
 * <p>Détection en cascade tolérante aux photos prises au téléphone (qualité variable,
 * bords courbés, fond chargé). Cinq étages tentés dans l'ordre :</p>
 *
 * <ol>
 *   <li><b>DNN (DocAligner)</b> — modèle ONNX (DocsaidLab, Apache 2.0) qui régresse
 *       directement les 4 coins via une heatmap 4 canaux. Plus précis que les
 *       approches classiques sur les documents bien définis (papier sur bureau,
 *       carte d'identité). Échoue proprement (heatmap quasi nulle) sur les
 *       images hors distribution, ce qui laisse les étages suivants prendre
 *       le relais. Voir {@link DocAlignerDocumentDetector}.</li>
 *   <li><b>Strict</b> — Canny + dilate léger + {@code approxPolyDP} epsilon=0.02
 *       sur 4 sommets exactement, marge 2 % aux bords. Cas idéal (document
 *       posé à plat sur fond uni). Pas de faux positifs.</li>
 *   <li><b>Souple</b> — {@code MORPH_CLOSE} large pour combler les trous d'arêtes
 *       (texte, ombres). {@code approxPolyDP} essayé à plusieurs epsilons
 *       (1 %, 2 %, 4 %, 6 %). Si pas de quadrilatère trouvé sur le contour,
 *       on retente sur son enveloppe convexe (lisse les petites bosses comme
 *       les coins arrondis ou la reliure). Marge 0,5 %. Score min appliqué.</li>
 *   <li><b>minAreaRect</b> — sur le plus grand contour substantiel, on prend le
 *       rectangle (potentiellement tourné) de surface minimale qui le contient.
 *       Toujours 4 coins par construction. Utile quand le document est globalement
 *       rectangulaire mais a des contours irréguliers (reliure de magazine).</li>
 *   <li><b>Otsu (page claire)</b> — seuillage {@code THRESH_BINARY | THRESH_OTSU} :
 *       la page (claire) passe à blanc, le fond sombre (bureau, ombre) à noir.
 *       {@code MORPH_CLOSE} comble le texte, puis {@code minAreaRect} sur le plus
 *       grand contour. Rattrape les pages sans bord net qui remplissent le cadre
 *       sur fond plus sombre — cas où Canny n'a aucune arête de page à saisir et
 *       s'accroche au contenu interne (titres, blocs colorés). Contrairement aux
 *       stages Canny, aucune marge aux bords n'est exigée (une page cadrée serré
 *       est à ras du cadre) ; les faux positifs « cadre entier » sont écartés par
 *       les gardes d'aire (≤ {@value #MAX_OTSU_AREA_RATIO}) et de solidité
 *       (une vraie page est un rectangle plein).</li>
 * </ol>
 *
 * <p>Score par candidat = {@code (quadArea / imageArea) × rectangularity}, où
 * {@code rectangularity} ∈ (0, 1] mesure à quel point le quadrilatère ressemble
 * à un vrai rectangle (rapport min/max des côtés opposés). Stage 2/3 rejettent
 * tout candidat sous {@code MIN_SCORE} pour limiter les faux positifs sur
 * fonds chargés.</p>
 *
 * <p>En cas d'échec total de tous les étages, retourne l'image originale inchangée.</p>
 */
@Service
@Slf4j(topic = "imageprocessing")
public class PerspectiveCorrectionService {

    /** Aire minimale du candidat stage 1 par rapport à l'image (20 %). */
    private static final double MIN_STRICT_AREA_RATIO = 0.20;

    /**
     * Aire minimale stage 2/3 (10 %). Plus tolérant que stage 1 car le candidat
     * doit aussi passer le filtre de score, qui combine aire et rectangularité.
     */
    private static final double MIN_LOOSE_AREA_RATIO = 0.10;

    /** Marge minimale entre le quadrilatère et les bords, stage 1 (2 % par côté). */
    private static final double STRICT_EDGE_MARGIN_RATIO = 0.02;

    /** Marge minimale stage 2/3 — phones cadrent souvent serré (0,5 %). */
    private static final double LOOSE_EDGE_MARGIN_RATIO = 0.005;

    /** Epsilons pour {@code approxPolyDP} en stage 2 (rapports du périmètre). */
    private static final double[] EPSILON_RATIOS_LOOSE = {0.01, 0.02, 0.04, 0.06};

    /**
     * Tailles de noyau {@code MORPH_CLOSE} en cascade stage 2/3, en fraction de min(w,h).
     * Petit pour bords nets (document propre), grand pour combler les espaces inter-articles
     * sur un magazine. On évalue chaque taille et on garde le meilleur score global.
     */
    private static final double[] CLOSE_KERNEL_RATIOS = {0.005, 0.015, 0.03, 0.05};

    /** Score minimal pour accepter un candidat stage 2/3. */
    private static final double MIN_SCORE = 0.15;

    /** Rectangularité minimale pour accepter un candidat stage 2 (filtre formes incohérentes). */
    private static final double MIN_RECTANGULARITY = 0.6;

    /** Stage 4 (Otsu) : ratio du noyau {@code MORPH_CLOSE}, comble le texte à l'intérieur de la page. */
    private static final double OTSU_CLOSE_KERNEL_RATIO = 0.03;

    /** Aire minimale de la page claire détectée par Otsu (25 % de l'image). */
    private static final double MIN_OTSU_AREA_RATIO = 0.25;

    /**
     * Aire maximale : au-delà, la région claire remplit quasiment tout le cadre —
     * signe que le fond est clair lui aussi (pas de page à découper). On abandonne.
     */
    private static final double MAX_OTSU_AREA_RATIO = 0.98;

    /**
     * Solidité minimale (aire du contour / aire de son {@code minAreaRect}). Une
     * page est un rectangle plein ⇒ ≈ 1. Filtre les taches claires morcelées
     * (reflets, ciel, nappe à motifs).
     */
    private static final double MIN_OTSU_SOLIDITY = 0.85;

    /**
     * Fraction minimale du contour Otsu qui doit coïncider avec une vraie arête
     * Canny. Une frontière de page (papier / bureau) produit un saut d'intensité
     * ⇒ arête Canny ; un dégradé lisse (mur, ciel) n'en produit pas — Otsu y crée
     * une frontière artificielle au seuil. Ce garde-fou rejette ces faux positifs.
     */
    private static final double MIN_OTSU_EDGE_SUPPORT = 0.15;

    /** Seuil d'imprécision pour éviter les comparaisons strictes avec zéro sur les flottants. */
    private static final double EPSILON = 1e-6;

    private final boolean available;
    private final DocAlignerDocumentDetector dnn;

    /**
     * Constructeur unique : Spring injecte le détecteur DNN, les tests qui veulent
     * exercer uniquement la cascade classique passent {@code null} explicitement.
     *
     * <p>Avant : un constructeur sans argument coexistait pour les tests, mais
     * Spring 4.3+ retombe sur le no-arg en présence de plusieurs constructeurs
     * non annotés — la production tournait silencieusement sans DNN. Garder un
     * seul constructeur élimine l'ambiguïté.</p>
     */
    public PerspectiveCorrectionService(@Nullable DocAlignerDocumentDetector dnn) {
        boolean loaded = false;
        try {
            try (var probe = new Mat()) {
                // Déclenche le chargement JNI
            }
            loaded = true;
            log.info("PerspectiveCorrectionService ready");
        } catch (Exception | UnsatisfiedLinkError e) {
            log.warn("PerspectiveCorrectionService unavailable error={}", e.getMessage());
        }
        this.available = loaded;
        this.dnn = (dnn != null && dnn.isAvailable()) ? dnn : null;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Résultat de la correction de perspective.
     *
     * @param image   l'image corrigée (ou l'originale si la détection a échoué)
     * @param applied true si la correction a été effectivement appliquée
     */
    public record Result(BufferedImage image, boolean applied) {}

    /**
     * Détecte le document et redresse sa perspective (warpPerspective vers rectangle).
     */
    public Result apply(BufferedImage image) {
        if (!available) {
            log.debug("Perspective correction skipped reason=opencv-unavailable");
            return new Result(image, false);
        }

        try {
            return withDetectedDocument(image, (src, quad) -> {
                // Le Mat redressé est une copie pleine résolution (plusieurs Mo
                // natifs) : on le ferme ici plutôt que de l'abandonner au
                // deallocator JavaCPP, cf. le commentaire de withDetectedDocument.
                try (var corrected = warpToRectangle(src, quad)) {
                    return matToBufferedImage(corrected);
                }
            });
        } catch (Exception e) {
            log.warn("Perspective correction failed error={}", e.getMessage());
            return new Result(image, false);
        }
    }

    /**
     * Détecte le document et recadre sur son rectangle englobant axis-aligned.
     */
    public Result crop(BufferedImage image) {
        if (!available) {
            log.debug("Crop skipped reason=opencv-unavailable");
            return new Result(image, false);
        }

        try {
            return withDetectedDocument(image, (src, quad) -> {
                // Idem apply() : le Rect et le clone recadré sont des Pointer
                // JavaCPP, fermés explicitement.
                try (var rect = boundingRectFromQuad(quad, src.cols(), src.rows());
                     var cropped = src.apply(rect);
                     var copy = cropped.clone()) {
                    return matToBufferedImage(copy);
                }
            });
        } catch (Exception e) {
            log.warn("Crop failed error={}", e.getMessage());
            return new Result(image, false);
        }
    }

    /**
     * Pipeline de détection en cascade. Renvoie le quadrilatère trouvé par le
     * premier étage qui réussit, ou {@code null} si tous échouent.
     */
    private Result withDetectedDocument(BufferedImage image, DocumentTransform transform) {
        var bgr = ensureBgr(image);
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();

        // Le BytePointer porte une copie NATIVE de la photo entière (~6 Mo pour
        // une page à 1754 px) ; le Mat ne fait que la référencer. Il est donc
        // déclaré AVANT le Mat pour être fermé APRÈS lui (ordre inverse en
        // try-with-resources). Laissé au deallocator JavaCPP, il n'était rendu
        // qu'au bon vouloir du GC : une fois le budget natif de JavaCPP atteint,
        // celui-ci n'échoue pas vite mais boucle sur System.gc() + sleep — le pod
        // se fige alors plusieurs secondes sans laisser la moindre ligne de log.
        try (var pixelPtr = new BytePointer(pixels);
             var src = new Mat(bgr.getHeight(), bgr.getWidth(), CV_8UC3, pixelPtr)) {
            // Stage 0 : DNN. Si confiance suffisante sur les 4 coins, on s'arrête là.
            Corner[] quad = dnnDetect(image);
            if (quad != null) {
                log.debug("Document detected stage=0-dnn");
            } else {
                quad = detectDocument(src);
            }
            if (quad == null) {
                log.debug("Document not detected stages=dnn,strict,loose,minAreaRect,otsu");
                return new Result(image, false);
            }
            var result = transform.apply(src, quad);
            return new Result(result, true);
        }
    }

    private Corner[] dnnDetect(BufferedImage image) {
        if (dnn == null) return null;
        return dnn.detect(image)
                .map(this::orderPoints)
                .orElse(null);
    }

    @FunctionalInterface
    private interface DocumentTransform {
        BufferedImage apply(Mat src, Corner[] quad);
    }

    private Corner[] detectDocument(Mat src) {
        int w = src.cols();
        int h = src.rows();
        double minDimension = Math.min((double) w, (double) h);

        try (var gray = new Mat();
             var blurred = new Mat();
             var edges = new Mat();
             var blurKernel = new Size(5, 5)) {

            cvtColor(src, gray, COLOR_BGR2GRAY);
            GaussianBlur(gray, blurred, blurKernel, 0);
            Canny(blurred, edges, 75, 200);

            // ===== Stage 1 — strict (existing behaviour) =====
            try (var dilateSize = new Size(3, 3);
                 var stage1Edges = edges.clone();
                 var smallKernel = getStructuringElement(MORPH_RECT, dilateSize)) {
                dilate(stage1Edges, stage1Edges, smallKernel);

                try (var contours = new MatVector();
                     var hierarchy = new Mat()) {
                    findContours(stage1Edges, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

                    var quad = findStrictQuadrilateral(contours, w, h);
                    if (quad != null) {
                        log.debug("Document detected stage=1-strict");
                        return quad;
                    }
                }
            }

            // ===== Stage 2 + 3 — cascade multi-noyaux =====
            // On essaye plusieurs tailles de MORPH_CLOSE et on garde le candidat avec
            // le meilleur score global (toutes tailles, stages 2 et 3 confondus).
            Candidate best = null;
            double bestKernelSize = -1.0;
            String bestSource = null;

            for (double ratio : CLOSE_KERNEL_RATIOS) {
                // Troncature (pas d'arrondi) : les ratios ont été calibrés sur ces tailles.
                int closeSize = Math.max(3, (int) (minDimension * ratio));
                if (closeSize % 2 == 0) closeSize++;

                try (var kernelSize = new Size(closeSize, closeSize);
                     var looseEdges = edges.clone();
                     var kernel = getStructuringElement(MORPH_RECT, kernelSize)) {
                    morphologyEx(looseEdges, looseEdges, MORPH_CLOSE, kernel);

                    try (var contours = new MatVector();
                         var hierarchy = new Mat()) {
                        findContours(looseEdges, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

                        var loose = findLooseCandidate(contours, w, h);
                        if (loose != null && (best == null || loose.score() > best.score())) {
                            best = loose;
                            bestKernelSize = closeSize;
                            bestSource = "2-loose";
                        }

                        var rect = findMinAreaRectCandidate(contours, w, h);
                        if (rect != null && (best == null || rect.score() > best.score())) {
                            best = rect;
                            bestKernelSize = closeSize;
                            bestSource = "3-minAreaRect";
                        }
                    }
                }
            }

            // ===== Stage 4 — page claire sur fond sombre (Otsu) =====
            // Rattrape les pages sans bord net qui remplissent le cadre : Canny n'a
            // pas d'arête de page à saisir et s'accroche au contenu interne. En
            // concurrence par score avec les candidats Canny (ne gagne que s'il est
            // meilleur), donc n'écrase pas une détection Canny propre.
            int otsuClose = Math.max(3, (int) (minDimension * OTSU_CLOSE_KERNEL_RATIO));
            if (otsuClose % 2 == 0) otsuClose++;
            var otsu = findOtsuPageCandidate(blurred, edges, w, h, otsuClose);
            if (otsu != null && (best == null || otsu.score() > best.score())) {
                best = otsu;
                bestKernelSize = otsuClose;
                bestSource = "4-otsu";
            }

            if (best != null) {
                log.debug("Document detected stage={} kernelPx={} score={}",
                        bestSource, (int) bestKernelSize, String.format("%.3f", best.score()));
                return best.quad();
            }
        }
        return null;
    }

    /**
     * Candidat scoré, utilisé pour comparer les sorties de plusieurs noyaux/stages
     * et retenir le meilleur.
     */
    private record Candidate(double score, Corner[] quad) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Candidate that)) return false;
            return Double.compare(that.score, score) == 0 && Arrays.equals(quad, that.quad);
        }

        @Override
        public int hashCode() {
            int result = Double.hashCode(score);
            result = 31 * result + Arrays.hashCode(quad);
            return result;
        }

        @Override
        public String toString() {
            return "Candidate[score=" + score + ", quad=" + Arrays.toString(quad) + "]";
        }
    }

    /**
     * Stage 1 : un seul epsilon, exactement 4 sommets, marge 2 %, premier match qui passe.
     * Conserve le comportement historique pour ne rien régresser sur les cas idéaux.
     */
    private Corner[] findStrictQuadrilateral(MatVector contours, int imgWidth, int imgHeight) {
        double imgArea = (double) imgWidth * (double) imgHeight;
        double minArea = imgArea * MIN_STRICT_AREA_RATIO;
        double marginX = (double) imgWidth * STRICT_EDGE_MARGIN_RATIO;
        double marginY = (double) imgHeight * STRICT_EDGE_MARGIN_RATIO;

        Corner[] best = null;
        double bestArea = 0.0;

        for (long i = 0; i < contours.size(); i++) {
            try (var contour = contours.get(i);
                 var approx = new Mat()) {

                double peri = arcLength(contour, true);
                approxPolyDP(contour, approx, 0.02 * peri, true);
                if (approx.rows() != 4) continue;

                double area = Math.abs(contourArea(approx));
                if (area < minArea || area <= bestArea) continue;

                var points = extractIntPoints(approx);
                if (points == null) continue;
                if (isTooCloseToEdges(points, imgWidth, imgHeight, marginX, marginY)) continue;

                best = points;
                bestArea = area;
            }
        }

        return best;
    }

    /**
     * Stage 2 : essaye plusieurs epsilons sur le contour brut puis sur son enveloppe
     * convexe. Renvoie le candidat avec le meilleur score, ou {@code null} si aucun
     * ne dépasse {@link #MIN_SCORE}.
     */
    private Candidate findLooseCandidate(MatVector contours, int imgWidth, int imgHeight) {
        double imgArea = (double) imgWidth * (double) imgHeight;
        double minArea = imgArea * MIN_LOOSE_AREA_RATIO;
        double marginX = (double) imgWidth * LOOSE_EDGE_MARGIN_RATIO;
        double marginY = (double) imgHeight * LOOSE_EDGE_MARGIN_RATIO;

        Corner[] best = null;
        double bestScore = MIN_SCORE;

        for (long i = 0; i < contours.size(); i++) {
            try (var contour = contours.get(i)) {
                double area = Math.abs(contourArea(contour));
                if (area < minArea) continue;

                var raw = approxToQuad(contour);
                if (raw != null) {
                    var score = scoreCandidate(raw, imgWidth, imgHeight, marginX, marginY, imgArea);
                    if (score > bestScore) {
                        bestScore = score;
                        best = raw;
                    }
                }

                try (var hull = new Mat()) {
                    convexHull(contour, hull, false, true);
                    var hulled = approxToQuad(hull);
                    if (hulled != null) {
                        var score = scoreCandidate(hulled, imgWidth, imgHeight, marginX, marginY, imgArea);
                        if (score > bestScore) {
                            bestScore = score;
                            best = hulled;
                        }
                    }
                }
            }
        }

        return best == null ? null : new Candidate(bestScore, best);
    }

    /**
     * Score d'un candidat = {@code (area / imageArea) × rectangularity}, ou {@code -1}
     * si le quadrilatère est trop proche des bords ou pas assez rectangulaire.
     */
    private double scoreCandidate(Corner[] quad, int imgWidth, int imgHeight,
                                  double marginX, double marginY, double imgArea) {
        if (isTooCloseToEdges(quad, imgWidth, imgHeight, marginX, marginY)) return -1.0;

        double rect = rectangularity(quad);
        if (rect < MIN_RECTANGULARITY) return -1.0;

        double area = quadrilateralArea(quad);
        return (area / imgArea) * rect;
    }

    /**
     * Stage 3 : sur le plus grand contour ≥ {@link #MIN_LOOSE_AREA_RATIO}, dérive
     * 4 coins via minAreaRect. Garantit un quad rectangulaire (rectangularity = 1
     * par construction).
     */
    private Candidate findMinAreaRectCandidate(MatVector contours, int imgWidth, int imgHeight) {
        double imgArea = (double) imgWidth * (double) imgHeight;
        double minArea = imgArea * MIN_LOOSE_AREA_RATIO;
        double marginX = (double) imgWidth * LOOSE_EDGE_MARGIN_RATIO;
        double marginY = (double) imgHeight * LOOSE_EDGE_MARGIN_RATIO;

        Mat largest = null;
        double largestArea = 0.0;

        try {
            for (long i = 0; i < contours.size(); i++) {
                try (var contour = contours.get(i)) {
                    double area = Math.abs(contourArea(contour));
                    if (area < minArea || area <= largestArea) continue;
                    if (largest != null) largest.close();
                    largest = contour.clone();
                    largestArea = area;
                }
            }

            if (largest == null) return null;

            try (var rect = minAreaRect(largest);
                 var corners = new Mat()) {
                boxPoints(rect, corners);
                var quad = extractFloatPoints(corners);
                if (quad == null) return null;
                if (isTooCloseToEdges(quad, imgWidth, imgHeight, marginX, marginY)) return null;

                double quadArea = quadrilateralArea(quad);
                double score = quadArea / imgArea; // rectangularity = 1 par construction
                if (score < MIN_SCORE) return null;
                return new Candidate(score, quad);
            }
        } finally {
            if (largest != null) largest.close();
        }
    }

    /**
     * Stage 4 : seuillage Otsu pour isoler une page claire sur fond plus sombre.
     * {@code THRESH_BINARY | THRESH_OTSU} met la page (claire) à blanc et le fond
     * (bureau, ombre) à noir ; {@code MORPH_CLOSE} comble le texte ; on prend le
     * {@code minAreaRect} du plus grand contour.
     *
     * <p>Contrairement aux stages Canny, on n'exige pas de marge aux bords : une
     * page photographiée serrée peut être à ras du cadre sur un ou deux côtés
     * (c'est justement le cas à récupérer). Les faux positifs « cadre entier »
     * sont écartés par la garde d'aire ({@link #MAX_OTSU_AREA_RATIO}) et de
     * solidité ({@link #MIN_OTSU_SOLIDITY} — une vraie page est un rectangle plein).</p>
     */
    private Candidate findOtsuPageCandidate(Mat blurred, Mat edges, int imgWidth, int imgHeight, int closeSize) {
        double imgArea = (double) imgWidth * (double) imgHeight;

        try (var kernelSize = new Size(closeSize, closeSize);
             var binary = new Mat();
             var kernel = getStructuringElement(MORPH_RECT, kernelSize)) {
            threshold(blurred, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);
            morphologyEx(binary, binary, MORPH_CLOSE, kernel);

            try (var contours = new MatVector();
                 var hierarchy = new Mat()) {
                findContours(binary, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

                Mat largest = null;
                double largestArea = 0.0;
                try {
                    for (long i = 0; i < contours.size(); i++) {
                        try (var contour = contours.get(i)) {
                            double area = Math.abs(contourArea(contour));
                            if (area <= largestArea) continue;
                            if (largest != null) largest.close();
                            largest = contour.clone();
                            largestArea = area;
                        }
                    }

                    if (largest == null) return null;
                    double areaRatio = largestArea / imgArea;
                    if (areaRatio < MIN_OTSU_AREA_RATIO || areaRatio > MAX_OTSU_AREA_RATIO) return null;

                    try (var rect = minAreaRect(largest);
                         var corners = new Mat()) {
                        boxPoints(rect, corners);
                        var quad = extractFloatPoints(corners);
                        if (quad == null) return null;

                        double rectArea = quadrilateralArea(quad);
                        if (rectArea <= 0.0) return null;
                        if (largestArea / rectArea < MIN_OTSU_SOLIDITY) return null; // solidité

                        double score = rectArea / imgArea; // rectangularity = 1 par construction
                        // Si le rectangle remplit quasiment tout le cadre, il n'y a pas de
                        // marge à rogner : aucune page à découper. Évite un faux positif
                        // « applied=true » (ex. photo plein cadre sans document) qui
                        // réécrirait le PDF pour un rognage négligeable.
                        if (score < MIN_SCORE || score > MAX_OTSU_AREA_RATIO) return null;

                        // La frontière doit être une vraie arête (papier/bureau), pas un
                        // seuil artificiel sur un dégradé lisse (mur, ciel, sans document).
                        double edgeFrac = edgeSupport(largest, edges, imgWidth, imgHeight);
                        if (edgeFrac < MIN_OTSU_EDGE_SUPPORT) {
                            log.debug("Otsu rejected reason=weak-edge-support frac={}",
                                    String.format("%.3f", edgeFrac));
                            return null;
                        }

                        return new Candidate(score, quad);
                    }
                } finally {
                    if (largest != null) largest.close();
                }
            }
        }
    }

    /**
     * Fraction du contour {@code otsuContour} qui coïncide (à ± quelques pixels)
     * avec une arête Canny de {@code edges}. Sert à distinguer une vraie frontière
     * de page (saut d'intensité ⇒ arête) d'un seuil Otsu artificiel sur un dégradé.
     */
    private static double edgeSupport(Mat otsuContour, Mat edges, int imgWidth, int imgHeight) {
        try (var black = new Scalar(0.0);
             var white = new Scalar(255.0, 0.0, 0.0, 0.0);
             var edgeKernelSize = new Size(5, 5);
             var mask = new Mat(imgHeight, imgWidth, CV_8UC1, black);
             var contours = new MatVector(otsuContour);
             var noHierarchy = new Mat();
             var offset = new Point(0, 0);
             var dilatedEdges = new Mat();
             var overlap = new Mat();
             var edgeKernel = getStructuringElement(MORPH_RECT, edgeKernelSize)) {
            drawContours(mask, contours, 0, white, 3, LINE_8, noHierarchy, Integer.MAX_VALUE, offset);
            dilate(edges, dilatedEdges, edgeKernel);
            bitwise_and(mask, dilatedEdges, overlap);
            double boundary = countNonZero(mask);
            if (boundary <= 0.0) return 0.0;
            return (double) countNonZero(overlap) / boundary;
        }
    }

    /**
     * Tente plusieurs epsilons d'approxPolyDP jusqu'à obtenir 4 sommets.
     */
    private Corner[] approxToQuad(Mat contourOrHull) {
        double peri = arcLength(contourOrHull, true);
        for (var eps : EPSILON_RATIOS_LOOSE) {
            try (var approx = new Mat()) {
                approxPolyDP(contourOrHull, approx, eps * peri, true);
                if (approx.rows() == 4) {
                    return extractIntPoints(approx);
                }
            }
        }
        return null;
    }

    /**
     * Rectangularité ∈ (0, 1] — ratios des côtés opposés. 1 pour un vrai rectangle.
     */
    private static double rectangularity(Corner[] quad) {
        double top = distance(quad[0], quad[1]);
        double bot = distance(quad[3], quad[2]);
        double left = distance(quad[0], quad[3]);
        double right = distance(quad[1], quad[2]);
        if (top < EPSILON || bot < EPSILON || left < EPSILON || right < EPSILON) {
            return 0.0;
        }

        double tb = Math.min(top, bot) / Math.max(top, bot);
        double lr = Math.min(left, right) / Math.max(left, right);
        return tb * lr;
    }

    /**
     * Aire d'un quadrilatère ordonné (TL, TR, BR, BL) via la formule du lacet.
     */
    private static double quadrilateralArea(Corner[] q) {
        double x0 = q[0].x();
        double y0 = q[0].y();
        double x1 = q[1].x();
        double y1 = q[1].y();
        double x2 = q[2].x();
        double y2 = q[2].y();
        double x3 = q[3].x();
        double y3 = q[3].y();

        double a = x0 * y1 - x1 * y0;
        double b = x1 * y2 - x2 * y1;
        double c = x2 * y3 - x3 * y2;
        double d = x3 * y0 - x0 * y3;
        return Math.abs(a + b + c + d) / 2.0;
    }

    /**
     * Rectangle englobant axis-aligned d'un quadrilatère, borné aux dimensions de l'image.
     *
     * <p>Les deux bords sont bornés indépendamment (floor du minimum, ceil du maximum,
     * puis clamp à l'image) : un coin hors cadre — le DNN en renvoie — ne décale plus
     * le bord opposé, et un pixel partiellement couvert reste dans le recadrage.
     * Package-private pour les tests.</p>
     */
    static Rect boundingRectFromQuad(Corner[] quad, int imgWidth, int imgHeight) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (var p : quad) {
            double px = p.x();
            double py = p.y();
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }

        int x = Math.max(0, (int) Math.floor(minX));
        int y = Math.max(0, (int) Math.floor(minY));
        int x2 = Math.min(imgWidth, (int) Math.ceil(maxX));
        int y2 = Math.min(imgHeight, (int) Math.ceil(maxY));

        int wRect = Math.max(0, x2 - x);
        int hRect = Math.max(0, y2 - y);
        return new Rect(x, y, wRect, hRect);
    }

    private Corner[] extractIntPoints(Mat approx) {
        if (approx.rows() != 4) return null;
        var points = new Corner[4];
        for (int i = 0; i < 4; i++) {
            var ptr = approx.ptr(i);
            float x = (float) ptr.getInt(0);
            float y = (float) ptr.getInt(4);
            points[i] = new Corner(x, y);
        }
        return orderPoints(points);
    }

    private Corner[] extractFloatPoints(Mat boxOutput) {
        if (boxOutput.rows() != 4) return null;
        var points = new Corner[4];
        for (int i = 0; i < 4; i++) {
            var ptr = boxOutput.ptr(i);
            float x = ptr.getFloat(0);
            float y = ptr.getFloat(4);
            points[i] = new Corner(x, y);
        }
        return orderPoints(points);
    }

    /**
     * Ordonne 4 points : top-left, top-right, bottom-right, bottom-left.
     */
    private Corner[] orderPoints(Corner[] pts) {
        double[] sums = new double[4];
        double[] diffs = new double[4];
        for (int i = 0; i < 4; i++) {
            double x = pts[i].x();
            double y = pts[i].y();
            sums[i] = x + y;
            diffs[i] = y - x;
        }

        var ordered = new Corner[4];
        ordered[0] = pts[indexOfMin(sums)];   // top-left
        ordered[1] = pts[indexOfMin(diffs)];  // top-right
        ordered[2] = pts[indexOfMax(sums)];   // bottom-right
        ordered[3] = pts[indexOfMax(diffs)];  // bottom-left
        return ordered;
    }

    private boolean isTooCloseToEdges(Corner[] points, int imgWidth, int imgHeight,
                                      double marginX, double marginY) {
        double w = imgWidth;
        double h = imgHeight;
        for (var p : points) {
            double px = p.x();
            double py = p.y();
            if (px < marginX || px > w - marginX || py < marginY || py > h - marginY) {
                return true;
            }
        }
        return false;
    }

    private Mat warpToRectangle(Mat src, Corner[] quad) {
        double widthTop = distance(quad[0], quad[1]);
        double widthBottom = distance(quad[3], quad[2]);
        int maxWidth = Math.max(1, (int) Math.round(Math.max(widthTop, widthBottom)));

        double heightLeft = distance(quad[0], quad[3]);
        double heightRight = distance(quad[1], quad[2]);
        int maxHeight = Math.max(1, (int) Math.round(Math.max(heightLeft, heightRight)));

        float[] srcArray = new float[]{
                quad[0].x(), quad[0].y(),
                quad[1].x(), quad[1].y(),
                quad[2].x(), quad[2].y(),
                quad[3].x(), quad[3].y()
        };
        float[] dstArray = new float[]{
                0.0f, 0.0f,
                (float) maxWidth, 0.0f,
                (float) maxWidth, (float) maxHeight,
                0.0f, (float) maxHeight
        };

        try (var srcPtr = new FloatPointer(srcArray);
             var dstPtr = new FloatPointer(dstArray);
             var srcPts = new Mat(4, 1, CV_32FC2, srcPtr);
             var dstPts = new Mat(4, 1, CV_32FC2, dstPtr);
             var mTransform = getPerspectiveTransform(srcPts, dstPts);
             var outSize = new Size(maxWidth, maxHeight);
             var warped = new Mat()) {

            warpPerspective(src, warped, mTransform, outSize);
            return warped.clone();
        }
    }

    private static double distance(Corner a, Corner b) {
        double dx = (double) a.x() - (double) b.x();
        double dy = (double) a.y() - (double) b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static int indexOfMin(double[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] < arr[idx]) idx = i;
        }
        return idx;
    }

    private static int indexOfMax(double[] arr) {
        int idx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[idx]) idx = i;
        }
        return idx;
    }

    private static BufferedImage ensureBgr(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return image;
        }
        var converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        var g = converted.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return converted;
    }

    private static BufferedImage matToBufferedImage(Mat mat) {
        int width = mat.cols();
        int height = mat.rows();
        var output = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        // Écriture directe dans le buffer physique (B,G,R), symétrique de la lecture
        // en entrée. NE PAS utiliser Raster.setDataElements : sur un TYPE_3BYTE_BGR
        // il attend l'ordre des bandes (R,G,B) et intervertit alors R/B avec les
        // octets BGR d'OpenCV (fond bois → bleu, blanc → rose sur photo couleur).
        byte[] target = ((DataBufferByte) output.getRaster().getDataBuffer()).getData();
        mat.data().get(target);
        return output;
    }
}
