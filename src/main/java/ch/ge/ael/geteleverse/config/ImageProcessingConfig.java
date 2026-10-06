package ch.ge.ael.geteleverse.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.global.opencv_core;
import org.springframework.context.annotation.Configuration;

/**
 * Borne le parallélisme interne des bibliothèques natives de traitement d'image.
 *
 * <p>OpenCV dimensionne son pool {@code parallel_for} sur le nombre de cœurs
 * <b>de la machine</b> : il lit {@code /proc/cpuinfo} (ou l'équivalent) et ignore
 * le quota CPU du cgroup. Sur un nœud à 64 cœurs, un pod limité à quelques vCPU
 * démarre donc 64 threads par appel OpenCV. Multiplié par
 * {@code app.admission.edit.max-concurrent} éditions en vol, cela produit une
 * sur-souscription massive : le cgroup throttle, les temps de réponse explosent
 * de façon erratique, et rien n'apparaît dans les logs — c'est l'un des profils
 * de latence sporadique observés sur {@code deskew}.</p>
 *
 * <p>La JVM, elle, <b>est</b> consciente du cgroup depuis {@code UseContainerSupport} :
 * {@code Runtime.availableProcessors()} renvoie le quota réel du pod. On s'en sert
 * comme valeur par défaut pour aligner OpenCV sur ce que le pod possède vraiment.</p>
 *
 * <p>Le réglage est <b>global au processus</b> ({@code cv::setNumThreads}), d'où sa
 * place dans une configuration de démarrage plutôt que dans le constructeur d'un
 * service. Le pendant côté ONNX Runtime — même problème, même cause — est posé par
 * {@code DocAlignerDocumentDetector} sur ses {@code SessionOptions}, l'API ORT
 * n'exposant ce réglage que par session.</p>
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class ImageProcessingConfig {

    private final ImageProcessingProperties properties;

    @PostConstruct
    public void pinOpenCvThreadPool() {
        int threads = properties.effectiveOpencvThreads();
        try {
            opencv_core.setNumThreads(threads);
            log.info("OpenCV thread pool pinned threads={} availableProcessors={} configured={}",
                    threads, Runtime.getRuntime().availableProcessors(), properties.opencvThreads());
        } catch (Exception | UnsatisfiedLinkError e) {
            // Natives absentes (le service de correction logue déjà l'indisponibilité) :
            // le pinning n'a alors plus d'objet, l'application démarre sans.
            log.warn("OpenCV thread pinning skipped error={}", e.getMessage());
        }
    }
}
