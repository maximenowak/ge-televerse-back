package ch.ge.ael.geteleverse.service;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditTraceTest {

    @Test
    void stage_recordsDurationAndOutcome_onSuccess() {
        var trace = new EditTrace();

        StepVerifier.create(trace.attachTo(EditTrace.stage("s3.get", Mono.just("x"))))
                .expectNext("x")
                .verifyComplete();

        assertThat(trace.summary()).matches("s3\\.get:\\d+ms:ok");
    }

    @Test
    void stage_recordsEmptyMonoAsOk() {
        var trace = new EditTrace();

        StepVerifier.create(trace.attachTo(EditTrace.stage("cache.put", Mono.<Void>empty())))
                .verifyComplete();

        assertThat(trace.summary()).matches("cache\\.put:\\d+ms:ok");
    }

    /**
     * Le cas qui motive la trace : la borne de duree expire pendant un maillon.
     * Le timeout annule l'amont, le maillon en vol est note `cancelled`, ceux
     * d'avant gardent leur issue -- le WARN designe ainsi le coupable.
     */
    @Test
    void stage_marksInFlightStageCancelled_whenTimeoutCutsIt() {
        var trace = new EditTrace();
        var pipeline = trace.attachTo(
                EditTrace.stage("s3.get", Mono.just("pdf"))
                        .then(EditTrace.stage("detect", Mono.never()))
                        .timeout(Duration.ofMillis(50)));

        StepVerifier.create(pipeline)
                .expectError(TimeoutException.class)
                .verify();

        assertThat(trace.summary()).matches("s3\\.get:\\d+ms:ok,detect:\\d+ms:cancelled");
    }

    @Test
    void stage_marksError_andLetsItThrough() {
        var trace = new EditTrace();

        StepVerifier.create(trace.attachTo(EditTrace.stage("s3.get", Mono.error(new IOException("boom")))))
                .expectError(IOException.class)
                .verify();

        assertThat(trace.summary()).matches("s3\\.get:\\d+ms:error");
    }

    /**
     * Sans trace dans le contexte (tests, appels hors chemin d'edition),
     * l'enveloppe est un passe-plat : la valeur circule, rien n'est enregistre.
     */
    @Test
    void stage_isPassThrough_withoutTraceInContext() {
        StepVerifier.create(EditTrace.stage("s3.get", Mono.just("x")))
                .expectNext("x")
                .verifyComplete();

        assertThat(EditTrace.NONE.summary()).isEqualTo("-");
    }

    /**
     * Chrono demarre a la souscription, pas au cablage : deux souscriptions
     * (retry, deuxieme tentative) donnent deux maillons.
     */
    @Test
    void stage_recordsOneEntryPerSubscription() {
        var trace = new EditTrace();
        var staged = trace.attachTo(EditTrace.stage("s3.get", Mono.just("x")));

        StepVerifier.create(staged).expectNext("x").verifyComplete();
        StepVerifier.create(staged).expectNext("x").verifyComplete();

        assertThat(trace.summary()).matches("s3\\.get:\\d+ms:ok,s3\\.get:\\d+ms:ok");
    }

    /**
     * Maillon synchrone (passe native) : `running` tant qu'il n'a pas rendu la
     * main -- c'est ce que montre le WARN quand la coupure tombe pendant OpenCV,
     * que l'interruption n'arrete pas.
     */
    @Test
    void time_showsRunningUntilDone_thenOk() throws Exception {
        var trace = new EditTrace();

        var result = trace.time("pdf.extract", () -> {
            assertThat(trace.summary()).matches("pdf\\.extract:\\d+ms:running");
            return 42;
        });

        assertThat(result).isEqualTo(42);
        assertThat(trace.summary()).matches("pdf\\.extract:\\d+ms:ok");
    }

    @Test
    void time_marksError_andRethrowsUnchanged() {
        var trace = new EditTrace();

        assertThatThrownBy(() -> trace.time("pdf.extract", () -> {
            throw new IOException("corrupt");
        })).isInstanceOf(IOException.class).hasMessage("corrupt");

        assertThat(trace.summary()).matches("pdf\\.extract:\\d+ms:error");
    }

    @Test
    void time_isPassThrough_onNone() throws Exception {
        assertThat(EditTrace.NONE.time("detect", () -> "x")).isEqualTo("x");
        assertThat(EditTrace.NONE.summary()).isEqualTo("-");
    }

    /**
     * Le motif employe par StorageService : la trace est lue du contexte dans un
     * deferContextual, puis passee au fromCallable qui chronometre ses etapes
     * synchrones. Les maillons reactifs et synchrones se suivent dans l'ordre.
     */
    @Test
    void from_insideCallable_recordsIntoTheTraceOfTheContext() {
        var trace = new EditTrace();
        var pipeline = trace.attachTo(
                EditTrace.stage("s3.get", Mono.just("pdf"))
                        .flatMap(pdf -> Mono.deferContextual(ctx -> {
                            var t = EditTrace.from(ctx);
                            return Mono.fromCallable(() -> t.time("detect", () -> pdf + "!"));
                        })));

        StepVerifier.create(pipeline).expectNext("pdf!").verifyComplete();

        assertThat(trace.summary()).matches("s3\\.get:\\d+ms:ok,detect:\\d+ms:ok");
    }
}
