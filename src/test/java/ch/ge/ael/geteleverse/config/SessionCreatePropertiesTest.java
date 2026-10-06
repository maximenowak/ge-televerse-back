package ch.ge.ael.geteleverse.config;

import ch.ge.ael.geteleverse.config.SessionCreateProperties.OriginProfile;
import ch.ge.ael.geteleverse.model.SizeProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre la dérivation du profil par suffixe d'Origin (longest-match, défaut S),
 * l'autorisation du realm de création cross-origin (frontière de label, https
 * only, apex, port), et la génération des patterns CORS.
 */
class SessionCreatePropertiesTest {

    private static SessionCreateProperties props() {
        return new SessionCreateProperties(
                List.of(".ge.ch", ".etat-ge.ch"),
                List.of(
                        new OriginProfile(".etat-ge.ch", SizeProfile.M),
                        new OriginProfile(".demarches.etat-ge.ch", SizeProfile.L)));
    }

    @Test
    void resolveProfile_longestMatchingSuffixWins() {
        // forms.demarches.etat-ge.ch matche .etat-ge.ch (M) ET .demarches.etat-ge.ch (L) → L.
        assertThat(props().resolveProfile("https://forms.demarches.etat-ge.ch"))
                .isEqualTo(SizeProfile.L);
    }

    @Test
    void resolveProfile_singleMatch() {
        assertThat(props().resolveProfile("https://guichet.etat-ge.ch"))
                .isEqualTo(SizeProfile.M);
    }

    @Test
    void resolveProfile_noFineMatch_defaultsToS() {
        assertThat(props().resolveProfile("https://www.example.com"))
                .isEqualTo(SizeProfile.DEFAULT);
        // .ge.ch est dans le realm (autorisé à créer) mais absent de la whitelist
        // fine → S quand même.
        assertThat(props().resolveProfile("https://x.ge.ch"))
                .isEqualTo(SizeProfile.DEFAULT);
    }

    @Test
    void resolveProfile_nonHttpsOrNull_defaultsToS() {
        assertThat(props().resolveProfile("http://guichet.etat-ge.ch"))
                .isEqualTo(SizeProfile.DEFAULT);
        assertThat(props().resolveProfile(null)).isEqualTo(SizeProfile.DEFAULT);
    }

    @Test
    void resolveProfile_ignoresPort() {
        assertThat(props().resolveProfile("https://guichet.etat-ge.ch:8443"))
                .isEqualTo(SizeProfile.M);
    }

    @Test
    void hasConfiguredProfile_trueOnlyForWhitelistedSuffix() {
        // Matche un suffixe de la whitelist fine → connu.
        assertThat(props().hasConfiguredProfile("https://guichet.etat-ge.ch")).isTrue();
        assertThat(props().hasConfiguredProfile("https://forms.demarches.etat-ge.ch")).isTrue();
        // Dans le realm (.ge.ch) mais absent de la whitelist fine → inconnu
        // (recevra S sans y être explicitement mappé).
        assertThat(props().hasConfiguredProfile("https://x.ge.ch")).isFalse();
        // Totalement hors périmètre, non-https, ou null → inconnu.
        assertThat(props().hasConfiguredProfile("https://www.example.com")).isFalse();
        assertThat(props().hasConfiguredProfile("http://guichet.etat-ge.ch")).isFalse();
        assertThat(props().hasConfiguredProfile(null)).isFalse();
    }

    @Test
    void hasConfiguredProfile_trueEvenWhenMappedToDefaultS() {
        // Un suffixe explicitement mappé sur S reste « connu » : à distinguer du
        // simple fallback par défaut (qui rend aussi S).
        var p = new SessionCreateProperties(
                List.of(".ge.ch"),
                List.of(new OriginProfile(".ge.ch", SizeProfile.S)));
        assertThat(p.resolveProfile("https://x.ge.ch")).isEqualTo(SizeProfile.DEFAULT);
        assertThat(p.hasConfiguredProfile("https://x.ge.ch")).isTrue();
    }

    @Test
    void isAllowedCreateOrigin_subdomainAndApex() {
        assertThat(props().isAllowedCreateOrigin("https://forms.ge.ch")).isTrue();
        assertThat(props().isAllowedCreateOrigin("https://ge.ch")).isTrue(); // apex
        assertThat(props().isAllowedCreateOrigin("https://demarches.etat-ge.ch")).isTrue();
    }

    @Test
    void isAllowedCreateOrigin_rejectsLabelBoundaryTricks() {
        assertThat(props().isAllowedCreateOrigin("https://evil-ge.ch")).isFalse();
        assertThat(props().isAllowedCreateOrigin("https://ge.ch.evil.com")).isFalse();
        assertThat(props().isAllowedCreateOrigin("http://forms.ge.ch")).isFalse(); // non-https
    }

    @Test
    void corsOriginPatterns_buildsHttpsWildcards() {
        // Suffixe avec ou sans point initial → pattern https://*<suffixe-doté>.
        var p = new SessionCreateProperties(List.of(".ge.ch", "etat-ge.ch"), List.of());
        assertThat(p.corsOriginPatterns())
                .containsExactly("https://*.ge.ch", "https://*.etat-ge.ch");
    }

    @Test
    void nullLists_normalizeToEmpty() {
        var p = new SessionCreateProperties(null, null);
        assertThat(p.allowedOriginSuffixes()).isEmpty();
        assertThat(p.profiles()).isEmpty();
        assertThat(p.resolveProfile("https://x.ge.ch")).isEqualTo(SizeProfile.DEFAULT);
        assertThat(p.isAllowedCreateOrigin("https://x.ge.ch")).isFalse();
        assertThat(p.corsOriginPatterns()).isEmpty();
    }
}
