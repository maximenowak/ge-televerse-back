package ch.ge.ael.geteleverse.testutil;

import ai.onnxruntime.OrtEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Probes statiques détectant la disponibilité des binaires natifs (OpenCV
 * bytedeco, ONNX Runtime) AVANT tout chargement des classes JNI.
 *
 * <p>Indispensable parce que sur certaines images CI minimales (Alpine,
 * distroless, builders sans glibc x86_64), la simple construction d'un
 * {@code org.bytedeco.opencv.opencv_core.Mat} crashe la JVM avec
 * {@code SIGABRT} (exit code 134) avant qu'un {@code try/catch} ne puisse
 * intervenir. Un {@code @EnabledIf} couplé à ce probe désactive proprement
 * les classes de tests dépendant des natives au lieu de tuer le fork
 * Surefire (ce qui empêcherait aussi les classes de tests suivantes
 * indépendantes des natives de s'exécuter).</p>
 *
 * <p>Sur Windows et macOS, les natives bytedeco bundlées fonctionnent sans
 * dépendance système particulière ; on suppose le probe positif. Sur Linux,
 * on vérifie l'existence du loader dynamique glibc x86_64 — son absence est
 * le signal le plus discriminant d'une base ultra-minimale incompatible avec
 * ces natives.</p>
 *
 * <p><b>ONNX Runtime fait l'objet d'un probe distinct</b> ({@link
 * #onnxAvailable()}) : contrairement à bytedeco, la supposition « Windows =
 * disponible » est fausse sur les postes équipés d'un EDR qui s'injecte dans
 * tous les processus. McAfee Endpoint Security, par exemple, précharge son
 * propre {@code MSVCP140.dll} 14.00.24210 (VS2015, 2016) ; le loader Windows
 * résolvant les imports par <em>nom de module</em>, {@code onnxruntime.dll}
 * s'y lie au lieu de celui de System32 et son initialisation échoue en
 * {@code ERROR_DLL_INIT_FAILED} (1114) à partir d'ONNX Runtime 1.21. Seule
 * une tentative de chargement réelle est fiable.</p>
 */
public final class NativeProbes {

    /**
     * Résultat mémoïsé : l'initialisation d'ONNX Runtime n'est tentée qu'une
     * fois par JVM. Indispensable avec {@code reuseForks=true} — un échec
     * marque {@code OrtEnvironment} en erreur, et les accès suivants lèvent
     * {@code NoClassDefFoundError} au lieu de l'{@code UnsatisfiedLinkError}
     * initial.
     */
    private static final boolean ONNX_AVAILABLE = probeOnnx();

    private NativeProbes() {}

    /** Cf. javadoc de la classe. */
    public static boolean nativesAvailable() {
        var osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("windows") || osName.contains("mac")) {
            return true;
        }
        return Files.exists(Path.of("/lib64/ld-linux-x86-64.so.2"))
                || Files.exists(Path.of("/lib/ld-linux-x86-64.so.2"));
    }

    /** Cf. javadoc de la classe. */
    public static boolean onnxAvailable() {
        return ONNX_AVAILABLE;
    }

    private static boolean probeOnnx() {
        if (!nativesAvailable()) {
            return false;
        }
        try {
            // Charge les natives ONNX. Contrairement au Mat bytedeco, l'échec
            // est rattrapable : le loader Windows/Linux rend la main sur une
            // LinkageError, il ne tue pas le fork Surefire (vérifié).
            OrtEnvironment.getEnvironment();
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
