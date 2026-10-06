package ch.ge.ael.geteleverse.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_xphoto.createSimpleWB;

/**
 * Service de correction de balance des blancs via OpenCV xphoto (SimpleWB).
 * Améliore la lisibilité des photos de documents en leur donnant un fond blanc propre.
 */
@Getter
@Service
@Slf4j(topic = "imageprocessing")
public class WhiteBalanceService {

    /**
     * -- GETTER --
     *  Indique si le service est opérationnel (natives OpenCV chargées).
     */
    private final boolean available;

    public WhiteBalanceService() {
        boolean loaded = false;
        try {
            // Tente de charger les natives OpenCV au démarrage (crée un Mat minimal)
            try (var probe = new Mat()) {
                // Mat() déclenche le chargement JNI via JavaCPP
            }
            loaded = true;
            log.info("WhiteBalanceService ready");
        } catch (Exception | UnsatisfiedLinkError e) {
            log.warn("WhiteBalanceService unavailable error={}", e.getMessage());
        }
        this.available = loaded;
    }

    /**
     * Applique la correction de balance des blancs (SimpleWB) sur l'image.
     * Si OpenCV n'est pas disponible, retourne l'image originale inchangée.
     *
     * @param image l'image source (typiquement une photo de document)
     * @return l'image corrigée, ou l'originale en cas d'indisponibilité/erreur
     */
    public BufferedImage apply(BufferedImage image) {
        if (!available) {
            log.debug("White balance skipped reason=opencv-unavailable");
            return image;
        }

        try {
            return applySimpleWB(image);
        } catch (Exception e) {
            log.warn("White balance failed error={}", e.getMessage());
            return image;
        }
    }

    private BufferedImage applySimpleWB(BufferedImage image) {
        var bgr = ensureBgr(image);
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();

        // Le BytePointer porte la copie native des pixels que le Mat référence :
        // déclaré AVANT lui pour être fermé APRÈS. Cf. PerspectiveCorrectionService
        // pour le motif (budget natif JavaCPP, boucle System.gc() sous pression).
        try (var pixelPtr = new BytePointer(pixels);
             var src = new Mat(bgr.getHeight(), bgr.getWidth(), CV_8UC3, pixelPtr);
             var dst = new Mat();
             var wb = createSimpleWB()) {

            wb.balanceWhite(src, dst);

            return matToBufferedImage(dst);
        }
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
