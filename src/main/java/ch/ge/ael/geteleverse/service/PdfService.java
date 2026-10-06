package ch.ge.ael.geteleverse.service;

import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import ch.ge.ael.geteleverse.controller.PageNotEditableException;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

@Service
@Slf4j(topic = "pdfprocessing")
public class PdfService {

    /** JPEG quality for thumbnails (0.0 - 1.0). */
    private static final float THUMBNAIL_JPEG_QUALITY = 0.75f;

    /**
     * Budget de pixels en entrée d'un décodage image (anti « image bomb » /
     * bombe de décompression : un fichier de quelques Mo peut déclarer des
     * dimensions gigantesques et faire exploser la RAM à la décompression).
     * 80 MP couvre les capteurs mobiles haut de gamme tout en bloquant les
     * dimensions aberrantes. Vérifié AVANT toute allocation de raster.
     */
    private static final long MAX_INPUT_PIXELS = 80_000_000L;

    /**
     * Plus grand côté autorisé pour le rendu d'une page PDF. Le format PDF
     * autorise des pages immenses ; rendue au DPI cible, une telle page
     * produirait un bitmap de plusieurs Go. On plafonne le DPI effectif pour
     * borner la dimension de sortie quelle que soit la taille de page d'un
     * fichier hostile (anti render-bomb).
     */
    private static final int MAX_RENDER_DIMENSION_PX = 4000;

    /**
     * Pixels déclarés maximum d'une image XObject d'une page à rendre. Le rendu
     * sous-échantillonne ces images à la résolution de sortie (cf.
     * {@link #renderPageBounded}), mais un PDF de quelques Ko peut déclarer une image de
     * 20000×20000 dont la seule décompression coûte des secondes de CPU : au-delà de ce
     * plafond, on refuse avant tout décodage. 250 MP laisse passer un A4 scanné à
     * 1200 dpi (~140 MP).
     */
    private static final long MAX_RENDER_SOURCE_PIXELS = 250_000_000L;

    private final int thumbnailWidth;
    private final int maxDimensionPx;
    private final float jpegQuality;
    private final int renderDpi;

    public PdfService(@Value("${app.thumbnail-width:120}") int thumbnailWidth,
                      ImageProcessingProperties imageProperties) {
        this.thumbnailWidth = thumbnailWidth;
        this.maxDimensionPx = imageProperties.maxDimensionPx();
        this.jpegQuality = imageProperties.jpegQuality();
        this.renderDpi = imageProperties.dpi();
    }

    /** Signature magique d'un fichier PDF : {@code %PDF-}. */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /**
     * Fenêtre de recherche de la signature. La spec PDF (note d'implémentation
     * Adobe) tolère un en-tête {@code %PDF-} n'importe où dans les 1024 premiers
     * octets, et le parseur PDFBox est laxiste de la même façon — la détection
     * reste donc alignée sur ce que {@code Loader.loadPDF} acceptera en aval.
     */
    private static final int PDF_MAGIC_WINDOW = 1024;

    /**
     * Détection PDF par le contenu (magic bytes), indépendante du Content-Type
     * déclaré et de l'extension — tous deux forgeables, et souvent faux sur
     * mobile. Cherche {@code %PDF-} dans la fenêtre des 1024 premiers octets.
     * Coût borné et purement en mémoire : à appeler sur des octets déjà
     * bufferisés, jamais sur un flux.
     */
    public static boolean isPdf(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        int lastStart = Math.min(bytes.length - PDF_MAGIC.length, PDF_MAGIC_WINDOW - 1);
        for (int start = 0; start <= lastStart; start++) {
            if (matchesPdfMagicAt(bytes, start)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPdfMagicAt(byte[] bytes, int start) {
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (bytes[start + i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convertit un fichier uploadé (image ou PDF) en bytes PDF.
     * Si c'est déjà un PDF (détecté par magic bytes via {@link #isPdf(byte[])}),
     * retourne tel quel. Sinon, tente une conversion image→PDF.
     */
    public byte[] convertToPdf(byte[] uploadBytes) throws IOException {
        if (isPdf(uploadBytes)) {
            return uploadBytes;
        }
        return imageToPdf(uploadBytes);
    }

    /**
     * Compte le nombre de pages d'un PDF.
     */
    public int countPdfPages(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            return doc.getNumberOfPages();
        }
    }

    /**
     * Découpe un PDF multi-pages en une liste de PDFs d'une seule page chacun.
     * Si le PDF ne contient qu'une page, retourne une liste avec le PDF original.
     */
    public List<byte[]> splitPdfToPages(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            if (doc.getNumberOfPages() <= 1) {
                return List.of(pdfBytes);
            }

            var splitter = new Splitter();
            var splitDocs = splitter.split(doc);

            var result = new java.util.ArrayList<byte[]>(splitDocs.size());
            for (var splitDoc : splitDocs) {
                try (splitDoc) {
                    var out = new ByteArrayOutputStream();
                    splitDoc.save(out);
                    result.add(out.toByteArray());
                }
            }
            return result;
        }
    }

    /**
     * Génère une vignette JPEG à partir d'une image ou de la première page d'un PDF.
     * Le type de la source est détecté par magic bytes ({@link #isPdf(byte[])}).
     *
     * @param sourceBytes les bytes du fichier source
     * @return les bytes JPEG de la vignette
     */
    public byte[] generateThumbnail(byte[] sourceBytes) throws IOException {
        return generateThumbnail(sourceBytes, thumbnailWidth);
    }

    /**
     * Génère une vignette JPEG à partir d'une image ou de la première page d'un PDF,
     * avec une largeur personnalisée.
     *
     * @param sourceBytes les bytes du fichier source
     * @param width       la largeur souhaitée en pixels
     * @return les bytes JPEG de la vignette
     */
    public byte[] generateThumbnail(byte[] sourceBytes, int width) throws IOException {
        BufferedImage image;
        if (isPdf(sourceBytes)) {
            image = renderPdfFirstPage(sourceBytes);
        } else {
            // Décodé au double de la largeur visée au moins : marge pour un
            // redimensionnement bicubique net, sans matérialiser la photo entière.
            image = decodeImageBounded(sourceBytes, 2 * width);
        }
        return createThumbnailJpeg(image, width);
    }

    /**
     * Fusionne plusieurs PDFs en un seul document.
     */
    public byte[] mergeMultiplePdfs(List<byte[]> pdfBytesList) throws IOException {
        if (pdfBytesList.isEmpty()) {
            throw new IllegalArgumentException("Aucune page à fusionner");
        }
        if (pdfBytesList.size() == 1) {
            return pdfBytesList.getFirst();
        }

        var merger = new PDFMergerUtility();
        for (var pdfBytes : pdfBytesList) {
            merger.addSource(new RandomAccessReadBuffer(pdfBytes));
        }

        var out = new ByteArrayOutputStream();
        merger.setDestinationStream(out);
        merger.mergeDocuments(null);

        return out.toByteArray();
    }

    /**
     * Rend la première page d'un PDF en image haute résolution (DPI configurable).
     * Utilisé pour le retraitement de pages existantes (ex. correction de perspective).
     */
    public BufferedImage renderPdfToImage(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            return renderPageBounded(doc, renderDpi);
        }
    }

    /**
     * Opérateurs admis sur une page « image seule » : état graphique, découpe (les PDF
     * passés par Ghostscript bornent souvent l'image par un {@code re W n}), couleur
     * sans peinture, contenu marqué — et {@code Do}, compté à part. Tout le reste
     * (texte, tracé peint, dégradé, image en ligne) signe un contenu que la
     * reconstruction depuis l'image effacerait.
     */
    private static final Set<String> IMAGE_PAGE_OPERATORS = Set.of(
            "q", "Q", "cm", "gs", "w", "J", "j", "M", "d", "ri", "i",
            "m", "l", "c", "v", "y", "h", "re", "W", "W*", "n",
            "CS", "cs", "SC", "SCN", "sc", "scn", "G", "g", "RG", "rg", "K", "k",
            "BMC", "BDC", "EMC", "MP", "DP");

    /**
     * Vrai si la page ne peint qu'une image : un seul {@code Do}, sur une image (aucun
     * XObject de formulaire, qui peut porter du texte), et aucun opérateur de texte ou
     * de peinture. C'est le cas des pages issues d'une photo ({@link #imageToPagePdf})
     * et de la plupart des pages de PDF numérisés ; pas d'un PDF produit par un
     * traitement de texte, ni d'un scan découpé en bandes (plusieurs images).
     */
    static boolean paintsASingleImage(PDPage page) throws IOException {
        var resources = page.getResources();
        if (resources == null) {
            return false;
        }
        for (var name : resources.getXObjectNames()) {
            if (!(resources.getXObject(name) instanceof PDImageXObject)) {
                return false;
            }
        }
        int drawn = 0;
        var parser = new PDFStreamParser(page);
        for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
            if (token instanceof Operator operator) {
                if ("Do".equals(operator.getName())) {
                    drawn++;
                } else if (!IMAGE_PAGE_OPERATORS.contains(operator.getName())) {
                    return false;
                }
            }
        }
        return drawn == 1;
    }

    /** Refuse une édition d'image sur une page qui ne peint pas une image seule. */
    private static void requireSingleImage(PDPage page) throws IOException {
        if (!paintsASingleImage(page)) {
            throw new PageNotEditableException();
        }
    }

    /**
     * Extrait l'image embarquée d'une page PDF. Retourne le bitmap source tel qu'il a
     * été stocké, sans inclure les marges blanches que produirait un rendu de la page
     * A4 entière. Indispensable pour les opérations qui itèrent (deskew, crop) afin
     * d'éviter le rétrécissement progressif du contenu.
     *
     * @param pdfBytes les octets du PDF (1 page)
     * @return l'image embarquée, ou {@code null} si la page ne contient aucun XObject image
     * @throws PageNotEditableException si la page peint autre chose que cette image :
     *         la page reconstruite depuis l'image perdrait le reste
     */
    public BufferedImage extractFirstImage(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            requireSingleImage(doc.getPage(0));
            var resources = doc.getPage(0).getResources();
            for (var name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject pdImage) {
                    guardImagePixels(pdImage.getWidth(), pdImage.getHeight());
                    return pdImage.getImage();
                }
            }
            return null;
        }
    }

    /**
     * Applique une transformation pixel sur l'image embarquée d'une page PDF
     * sans toucher à la géométrie de la page. Évite le round-trip
     * render→transform→re-embed qui rétrécit le contenu à chaque appel
     * (le rendu inclut les marges blanches A4 et tout est re-fitté ensuite).
     *
     * <p>La page doit ne peindre que son image : c'est le cas des pages issues de
     * {@link #convertToPdf(byte[])}, pas forcément de celles d'un PDF téléversé, qui
     * passent telles quelles.</p>
     *
     * @param pdfBytes  les octets du PDF (1 page)
     * @param transform la transformation à appliquer sur l'image embarquée
     * @return les octets du PDF avec l'image transformée, géométrie inchangée
     * @throws PageNotEditableException si la page peint autre chose que son image
     */
    public byte[] transformFirstImage(byte[] pdfBytes,
                                      java.util.function.UnaryOperator<BufferedImage> transform)
            throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            requireSingleImage(doc.getPage(0));
            var resources = doc.getPage(0).getResources();
            for (var name : resources.getXObjectNames()) {
                if (resources.getXObject(name) instanceof PDImageXObject pdImage) {
                    guardImagePixels(pdImage.getWidth(), pdImage.getHeight());
                    var transformed = transform.apply(pdImage.getImage());
                    var replacement = JPEGFactory.createFromImage(doc, transformed, jpegQuality);
                    resources.put(name, replacement);
                    break;
                }
            }
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Rotation lossless d'une page PDF via le champ /Rotate (métadonnée).
     * Aucun ré-encodage : préserve la qualité et la taille du contenu d'origine
     * (un round-trip render→rotate→re-embed rétrécit le contenu à chaque appel
     * car le bitmap rendu inclut les marges blanches A4).
     *
     * @param pdfBytes  les octets du PDF d'origine (1 page)
     * @param clockwise true = +90°, false = -90°
     * @return les octets du PDF avec /Rotate mis à jour
     */
    public byte[] rotatePdfPage(byte[] pdfBytes, boolean clockwise) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            var page = doc.getPage(0);
            int delta = clockwise ? 90 : -90;
            int next = Math.floorMod(page.getRotation() + delta, 360);
            page.setRotation(next);

            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Rotation de 90° d'une image.
     *
     * @param image     l'image source
     * @param clockwise true = 90° horaire, false = 90° anti-horaire
     * @return l'image tournée (width/height inversés)
     */
    public BufferedImage rotateImage(BufferedImage image, boolean clockwise) {
        int w = image.getWidth();
        int h = image.getHeight();

        var rotated = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        var g2d = rotated.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        if (clockwise) {
            g2d.translate(h, 0);
            g2d.rotate(Math.PI / 2);
        } else {
            g2d.translate(0, w);
            g2d.rotate(-Math.PI / 2);
        }

        g2d.drawImage(image, 0, 0, null);
        g2d.dispose();
        return rotated;
    }

    /**
     * Rotation {@code /Rotate} de la première page, normalisée dans [0, 360).
     * Métadonnée posée par {@link #rotatePdfPage} : à reporter sur toute page
     * reconstruite depuis l'image embarquée, qui, elle, n'est jamais tournée.
     */
    public int pageRotation(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            return Math.floorMod(doc.getPage(0).getRotation(), 360);
        }
    }

    /**
     * Convertit un BufferedImage en PDF (page A4, redimensionné, compressé JPEG 75%).
     * Utilisé pour le retraitement de pages existantes après correction d'image.
     */
    public byte[] imageToPagePdf(BufferedImage image) throws IOException {
        return imageToPagePdf(image, 0);
    }

    /**
     * Variante de {@link #imageToPagePdf(BufferedImage)} qui pose la rotation
     * {@code /Rotate} de la page source. Recadrage et redressement travaillent sur
     * l'image embarquée, jamais tournée : sans ce report, une page tournée par
     * l'usager puis recadrée revenait de travers.
     *
     * @param rotation rotation de la page source (cf. {@link #pageRotation(byte[])})
     */
    public byte[] imageToPagePdf(BufferedImage image, int rotation) throws IOException {
        var resized = resizeImage(image);

        try (var doc = new PDDocument()) {
            var pageSize = a4PageFor(resized);
            var page = new PDPage(pageSize);
            if (rotation != 0) {
                page.setRotation(rotation);
            }
            doc.addPage(page);

            double pageWidth = pageSize.getWidth();
            double pageHeight = pageSize.getHeight();
            double margin = 36.0;

            double usableWidth = pageWidth - 2.0 * margin;
            double usableHeight = pageHeight - 2.0 * margin;

            double imgWidth = resized.getWidth();
            double imgHeight = resized.getHeight();

            double scale = Math.min(usableWidth / imgWidth, usableHeight / imgHeight);
            if (scale > 1.0) {
                scale = 1.0;
            }

            double drawWidth = imgWidth * scale;
            double drawHeight = imgHeight * scale;
            double x = margin + (usableWidth - drawWidth) / 2.0;
            double y = margin + (usableHeight - drawHeight) / 2.0;

            var pdImage = JPEGFactory.createFromImage(doc, resized, jpegQuality);

            try (var cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, (float) x, (float) y, (float) drawWidth, (float) drawHeight);
            }

            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private BufferedImage renderPdfFirstPage(byte[] pdfBytes) throws IOException {
        try (var doc = Loader.loadPDF(pdfBytes)) {
            return renderPageBounded(doc, 72.0f);
        }
    }

    /**
     * Rend la première page en bornant le plus grand côté à
     * {@link #MAX_RENDER_DIMENSION_PX}. Pour une page normale (A4) le DPI
     * demandé passe inchangé ; pour une page au MediaBox hostile, le DPI
     * effectif est réduit afin que le bitmap de sortie reste borné.
     */
    private BufferedImage renderPageBounded(PDDocument doc, float dpi) throws IOException {
        var page = doc.getPage(0);
        guardRenderSources(page);
        var box = page.getCropBox();
        double longestPt = Math.max(box.getWidth(), box.getHeight());
        float effectiveDpi = dpi;

        if (longestPt > 0.0) {
            double maxDpi = (double) MAX_RENDER_DIMENSION_PX * 72.0 / longestPt;
            if (effectiveDpi > maxDpi) {
                effectiveDpi = (float) maxDpi;
            }
        }

        // Sous-échantillonnage : chaque image de la page est décodée à la résolution
        // de SORTIE, et non à la sienne. Sans lui, borner le bitmap rendu ne bornait
        // rien en amont — PDFBox décodait une image embarquée de 20000×20000 à pleine
        // résolution (> 1 Go de tas) pour une vignette de 120 px, et l'OutOfMemoryError
        // n'est rattrapée par aucun onErrorResume.
        var renderer = new PDFRenderer(doc);
        renderer.setSubsamplingAllowed(true);
        return renderer.renderImageWithDPI(0, effectiveDpi);
    }

    /**
     * Refuse une page dont une image XObject déclare plus de
     * {@link #MAX_RENDER_SOURCE_PIXELS} pixels — lecture du dictionnaire seule, avant
     * tout décodage.
     */
    private static void guardRenderSources(PDPage page) throws IOException {
        var resources = page.getResources();
        if (resources == null) return;
        for (var name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDImageXObject image
                    && (long) image.getWidth() * image.getHeight() > MAX_RENDER_SOURCE_PIXELS) {
                throw new IOException("Image embarquée trop volumineuse pour le rendu: %dx%d px (max %d MP)"
                        .formatted(image.getWidth(), image.getHeight(), MAX_RENDER_SOURCE_PIXELS / 1_000_000));
            }
        }
    }

    /**
     * Refuse une image dont les dimensions déclarées dépassent
     * {@link #MAX_INPUT_PIXELS}, avant toute allocation de raster.
     */
    private static void guardImagePixels(long width, long height) throws IOException {
        if (width * height > MAX_INPUT_PIXELS) {
            throw new IOException("Image trop volumineuse: %dx%d px (max %d MP)"
                    .formatted(width, height, MAX_INPUT_PIXELS / 1_000_000));
        }
    }

    /**
     * Décode une image en bornant les dimensions AVANT décompression : lit la
     * taille déclarée dans l'en-tête via un {@link ImageReader}, refuse au-delà
     * du budget pixels, puis décode. Bloque les bombes de décompression que
     * {@link ImageIO#read(java.io.InputStream)} matérialiserait en RAM avant
     * tout contrôle de taille.
     *
     * <p>Sous-échantillonne au décodage quand l'image dépasse largement la taille
     * utile : le pas est le plus grand entier qui garde le plus grand côté décodé
     * ≥ {@code targetLongestSide}, donc le redimensionnement qui suit reste une
     * réduction bicubique. Une photo de 80 MP visée à 3508 px est ainsi décodée au
     * quart de ses pixels.</p>
     *
     * @param targetLongestSide plus grand côté utile en aval ; ≤ 0 pour décoder
     *                          à pleine résolution
     */
    private static BufferedImage decodeImageBounded(byte[] bytes, int targetLongestSide) throws IOException {
        try (var iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new IOException("Flux image illisible");
            }
            var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("Format d'image non reconnu");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                guardImagePixels(width, height);
                var param = reader.getDefaultReadParam();
                int step = targetLongestSide > 0 ? Math.max(1, Math.max(width, height) / targetLongestSide) : 1;
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                var image = reader.read(0, param);
                if (image == null) {
                    throw new IOException("Impossible de décoder l'image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private byte[] createThumbnailJpeg(BufferedImage source) throws IOException {
        return createThumbnailJpeg(source, thumbnailWidth);
    }

    private byte[] createThumbnailJpeg(BufferedImage source, int targetWidth) throws IOException {
        int origW = source.getWidth();
        int origH = source.getHeight();

        double scale = (double) targetWidth / origW;
        int newW = targetWidth;
        int newH = (int) Math.round(origH * scale);

        var thumbnail = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        var g2d = thumbnail.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(source, 0, 0, newW, newH, null);
        g2d.dispose();

        var out = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(THUMBNAIL_JPEG_QUALITY);
        writer.setOutput(ImageIO.createImageOutputStream(out));
        writer.write(null, new IIOImage(thumbnail, null, null), param);
        writer.dispose();

        return out.toByteArray();
    }

    private byte[] imageToPdf(byte[] imageBytes) throws IOException {
        // Ordre voulu : décodage sous-échantillonné, réduction, PUIS orientation — sur
        // l'image déjà réduite. Orienter la photo pleine résolution coûtait deux copies
        // pleine taille (intermédiaire ARGB de l'AffineTransformOp bicubique + copie
        // orientée) avant même la réduction : ~880 Mo de tas pour 80 MP en
        // Orientation=6. Le plafond de resizeImage étant le même sur les deux axes,
        // réduire avant d'orienter donne exactement les mêmes dimensions.
        var decoded = decodeImageBounded(imageBytes, maxDimensionPx);
        var resized = resizeImage(decoded);
        var oriented = applyExifOrientation(resized, imageBytes);

        try (var doc = new PDDocument()) {
            var pageSize = a4PageFor(oriented);
            var page = new PDPage(pageSize);
            doc.addPage(page);

            double pageWidth = pageSize.getWidth();
            double pageHeight = pageSize.getHeight();
            double margin = 36.0; // 0.5 inch

            double usableWidth = pageWidth - 2.0 * margin;
            double usableHeight = pageHeight - 2.0 * margin;

            double imgWidth = oriented.getWidth();
            double imgHeight = oriented.getHeight();

            double scale = Math.min(usableWidth / imgWidth, usableHeight / imgHeight);
            if (scale > 1.0) {
                scale = 1.0; // don't upscale
            }

            double drawWidth = imgWidth * scale;
            double drawHeight = imgHeight * scale;
            double x = margin + (usableWidth - drawWidth) / 2.0;
            double y = margin + (usableHeight - drawHeight) / 2.0;

            var pdImage = JPEGFactory.createFromImage(doc, oriented, jpegQuality);

            try (var cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(pdImage, (float) x, (float) y, (float) drawWidth, (float) drawHeight);
            }

            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Applique l'orientation EXIF de la photo source au BufferedImage décodé.
     * ImageIO ignore le tag EXIF Orientation : une photo portrait prise au
     * téléphone est typiquement stockée en pixels paysage + Orientation=6
     * ("rotate 90° CW"). Sans cette correction, le PDF généré utiliserait
     * une page paysage et l'image apparaîtrait couchée.
     *
     * <p>Les huit valeurs EXIF (1..8) sont gérées (rotations + miroirs).
     * En cas d'absence de métadonnée ou de format non-JPEG, l'image est
     * retournée telle quelle.
     */
    private BufferedImage applyExifOrientation(BufferedImage image, byte[] sourceBytes) {
        int orientation = readExifOrientation(sourceBytes);
        if (orientation <= 1 || orientation > 8) {
            return image;
        }

        int w = image.getWidth();
        int h = image.getHeight();
        boolean swapDims = orientation >= 5;
        int targetW = swapDims ? h : w;
        int targetH = swapDims ? w : h;

        var transform = new AffineTransform();
        switch (orientation) {
            case 2 -> { transform.scale(-1, 1); transform.translate(-w, 0); }
            case 3 -> { transform.translate(w, h); transform.rotate(Math.PI); }
            case 4 -> { transform.scale(1, -1); transform.translate(0, -h); }
            case 5 -> { transform.rotate(Math.PI / 2); transform.scale(1, -1); }
            case 6 -> { transform.translate(h, 0); transform.rotate(Math.PI / 2); }
            case 7 -> { transform.translate(h, w); transform.scale(1, -1); transform.rotate(Math.PI / 2); }
            case 8 -> { transform.translate(0, w); transform.rotate(-Math.PI / 2); }
            default -> { /* unreachable, guarded above */ }
        }

        // Quarts de tour et miroirs uniquement : chaque pixel source tombe exactement
        // sur un pixel cible, l'interpolation est inutile. Plus proche voisin, dessiné
        // directement dans la cible — pas d'AffineTransformOp bicubique, dont le
        // filtrage allouait un intermédiaire TYPE_INT_ARGB de la taille de l'image.
        var oriented = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        var g2d = oriented.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.drawImage(image, transform, null);
        g2d.dispose();

        log.debug("Image EXIF-oriented orientation={} fromW={} fromH={} toW={} toH={}",
                orientation, w, h, targetW, targetH);
        return oriented;
    }

    private int readExifOrientation(byte[] sourceBytes) {
        try {
            var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(sourceBytes));
            var dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception e) {
            log.debug("EXIF unreadable, skip orientation: {}", e.getMessage());
        }
        return 1;
    }

    private BufferedImage resizeImage(BufferedImage original) {
        int w = original.getWidth();
        int h = original.getHeight();

        if (w <= maxDimensionPx && h <= maxDimensionPx) {
            return original;
        }

        double scale = Math.min((double) maxDimensionPx / w, (double) maxDimensionPx / h);
        int newW = (int) Math.round(w * scale);
        int newH = (int) Math.round(h * scale);

        log.debug("Image resized fromW={} fromH={} toW={} toH={}", w, h, newW, newH);

        var resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        var g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.drawImage(original, 0, 0, newW, newH, null);
        g2d.dispose();

        return resized;
    }

    /**
     * A4 portrait pour les images plus hautes que larges (ou carrées),
     * A4 paysage sinon. Évite le letterboxing des photos horizontales.
     */
    private static PDRectangle a4PageFor(BufferedImage image) {
        if (image.getWidth() > image.getHeight()) {
            return new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        }
        return PDRectangle.A4;
    }
}
