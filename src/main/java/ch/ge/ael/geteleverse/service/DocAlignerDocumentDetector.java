package ch.ge.ael.geteleverse.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ch.ge.ael.geteleverse.config.ImageProcessingProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Optional;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_dnn.blobFromImage;

/**
 * Détection des 4 coins d'un document via le modèle DocAligner (DocsaidLab,
 * Apache 2.0). Régression par heatmap : 4 canaux, 1 coin par canal, argmax
 * → coordonnée image.
 *
 * <p>Plus précis que la cascade classique de {@link PerspectiveCorrectionService}
 * sur les documents bien définis (papier sur bureau, carte d'identité, reçu) —
 * gère naturellement la perspective, les plis, les ombres. Échoue proprement
 * (heatmap quasi nulle, méthode renvoie {@code Optional.empty()}) sur les
 * images hors distribution (ex. magazine sur fond chargé sans bord net), ce
 * qui laisse la cascade classique prendre le relais.</p>
 *
 * <p>Modèle : {@code lcnet100_h_e_bifpn_256_fp32.onnx} (~4.7 MB, embarqué dans
 * le jar). Chargé une fois au démarrage de l'application, partagé entre toutes
 * les requêtes (l'OrtSession est documentée comme thread-safe).</p>
 *
 * <p>Préprocessing conforme à {@code docaligner/heatmap_reg/infer.py} de
 * DocsaidLab : BGR (pas de swap), redimensionnement 256×256 (aspect ratio
 * écrasé), normalisation /255, layout NCHW (1, 3, 256, 256) float32.</p>
 *
 * <p>Postprocess : par canal, argmax de la heatmap. Si le pic max d'un canal
 * est inférieur au seuil DocAligner ({@value #HEATMAP_THRESHOLD}), on considère
 * qu'il manque un coin et on renvoie {@code Optional.empty()} — le service
 * appelant tombera sur la cascade classique.</p>
 */
@Service
@Slf4j(topic = "imageprocessing")
public class DocAlignerDocumentDetector {

    private static final String MODEL_RESOURCE = "/models/lcnet100_h_e_bifpn_256_fp32.onnx";
    private static final String MODEL_INPUT_NAME = "img";
    private static final int MODEL_INPUT_SIZE = 256;
    private static final float HEATMAP_THRESHOLD = 0.3f;
    private static final int CORNER_CHANNELS = 4;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final boolean available;

    /**
     * Constructeur unique (cf. la note d'ambiguïté dans
     * {@link PerspectiveCorrectionService}) : Spring injecte les paramètres de
     * traitement d'image, dont le nombre de threads ORT.
     */
    public DocAlignerDocumentDetector(ImageProcessingProperties properties) {
        OrtEnvironment localEnv = null;
        OrtSession localSession = null;
        boolean loaded = false;
        try {
            byte[] modelBytes = loadModelBytes();
            localEnv = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
                // Pools ORT bornés explicitement : comme OpenCV, ONNX Runtime
                // dimensionne son pool intra-opérateur sur les cœurs de la MACHINE
                // et ignore le quota CPU du cgroup. Laissé par défaut, chacune des
                // `app.admission.edit.max-concurrent` inférences en vol réclamait
                // un pool large comme le nœud — sur-souscription, throttling cgroup,
                // latences erratiques et silencieuses. Le parallélisme utile vient
                // ici des requêtes concurrentes, pas de l'intérieur d'une inférence.
                opts.setIntraOpNumThreads(properties.onnxIntraOpThreads());
                opts.setInterOpNumThreads(1);
                long t0 = System.nanoTime();
                localSession = localEnv.createSession(modelBytes, opts);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.info("DocAligner loaded durationMs={} modelBytes={} intraOpThreads={}",
                        ms, modelBytes.length, properties.onnxIntraOpThreads());
            }
            loaded = true;
        } catch (Exception | LinkageError e) {
            // LinkageError et pas seulement UnsatisfiedLinkError : si les natives
            // ONNX ont déjà échoué dans cette JVM, OrtEnvironment est marquée en
            // erreur et les instanciations suivantes lèvent NoClassDefFoundError.
            log.warn("DocAligner unavailable error={}", e.getMessage());
        }
        this.env = localEnv;
        this.session = localSession;
        this.available = loaded;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Renvoie les 4 coins du document détecté (dans l'ordre brut des canaux du
     * modèle, à réordonner par l'appelant via {@code orderPoints}), ou
     * {@code Optional.empty()} si le détecteur est indisponible, si l'image
     * est hors distribution, ou si la heatmap d'au moins un coin est sous
     * le seuil de confiance.
     */
    public Optional<Corner[]> detect(BufferedImage image) {
        if (!available) return Optional.empty();
        try {
            return runInference(image);
        } catch (Exception e) {
            log.warn("DocAligner inference failed error={}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Corner[]> runInference(BufferedImage image) throws Exception {
        BufferedImage bgr = ensureBgr(image);
        byte[] pixels = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        int origW = bgr.getWidth();
        int origH = bgr.getHeight();

        // Préprocessing : BGR (pas de swap), /255, NCHW. blobFromImage produit
        // directement le tensor (1, 3, 256, 256) float32 ; on en extrait le
        // tableau plat dans l'ordre NCHW pour onnxruntime.
        //
        // Tous les Pointer JavaCPP sont fermés explicitement. Le BytePointer en
        // particulier porte une copie NATIVE de la photo entière (~6 Mo pour une
        // page à 1754 px) que le Mat ne fait que référencer : il doit donc être
        // déclaré AVANT le Mat pour être fermé APRÈS lui. Laissés au deallocator
        // JavaCPP, ces octets n'étaient rendus qu'au bon vouloir du GC — et quand
        // le budget natif de JavaCPP est atteint, celui-ci n'échoue pas vite : il
        // boucle sur System.gc() + sleep, ce qui fige le pod sans laisser un log.
        float[] inputData = new float[3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE];
        try (BytePointer pixelPtr = new BytePointer(pixels);
             Mat src = new Mat(origH, origW, CV_8UC3, pixelPtr);
             Size inputSize = new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE);
             Scalar noMean = new Scalar(0, 0, 0, 0);
             Mat blob = blobFromImage(src, 1.0 / 255.0, inputSize, noMean, false, false, CV_32F)) {
            FloatIndexer idx = blob.createIndexer();
            try {
                int i = 0;
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < MODEL_INPUT_SIZE; y++) {
                        for (int x = 0; x < MODEL_INPUT_SIZE; x++) {
                            inputData[i++] = idx.get(0, c, y, x);
                        }
                    }
                }
            } finally {
                idx.release();
            }
        }

        long[] runShape = {1, 3, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE};
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), runShape);
             OrtSession.Result result = session.run(Map.of(MODEL_INPUT_NAME, inputTensor))) {

            OnnxValue out = result.get(0);
            if (!(out instanceof OnnxTensor outTensor)) return Optional.empty();
            Object value = outTensor.getValue();
            if (!(value instanceof float[][][][] hm)) return Optional.empty();
            if (hm.length == 0 || hm[0].length < CORNER_CHANNELS) return Optional.empty();

            int outH = hm[0][0].length;
            int outW = hm[0][0][0].length;
            double sx = (double) origW / outW;
            double sy = (double) origH / outH;

            Corner[] corners = new Corner[CORNER_CHANNELS];
            for (int c = 0; c < CORNER_CHANNELS; c++) {
                int bestX = 0, bestY = 0;
                float bestV = Float.NEGATIVE_INFINITY;
                for (int y = 0; y < outH; y++) {
                    for (int x = 0; x < outW; x++) {
                        float v = hm[0][c][y][x];
                        if (v > bestV) {
                            bestV = v;
                            bestX = x;
                            bestY = y;
                        }
                    }
                }
                if (bestV < HEATMAP_THRESHOLD) {
                    log.debug("DocAligner below threshold channel={} peak={} threshold={}",
                            c, bestV, HEATMAP_THRESHOLD);
                    return Optional.empty();
                }
                corners[c] = new Corner((float) (bestX * sx), (float) (bestY * sy));
            }
            return Optional.of(corners);
        }
    }

    private byte[] loadModelBytes() throws Exception {
        try (InputStream is = getClass().getResourceAsStream(MODEL_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Modèle ONNX absent du classpath : " + MODEL_RESOURCE);
            }
            return is.readAllBytes();
        }
    }

    @PreDestroy
    public void cleanup() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("OrtSession close failed error={}", e.getMessage());
            }
        }
        // OrtEnvironment est un singleton géré par onnxruntime — on ne le ferme pas.
    }

    private static BufferedImage ensureBgr(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR) return image;
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        var g = converted.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return converted;
    }
}
