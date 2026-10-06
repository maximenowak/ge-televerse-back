package ch.ge.ael.geteleverse.config.logging;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.netty.channel.AbortedException;

/**
 * Hooks Reactor pour étouffer le bruit lié aux clients qui ferment la
 * connexion en cours d'upload. Sans hook, {@code Operators.onErrorDropped}
 * log un ERROR ({@code NativeIoException: recvAddress(..) failed ... Connection
 * reset by peer}) à chaque déconnexion — comportement normal côté mobile.
 */
@Configuration
@Slf4j
public class ReactorHooksConfiguration {

    @PostConstruct
    void installHooks() {
        Hooks.onErrorDropped(t -> {
            if (AbortedException.isConnectionReset(t)) {
                log.debug("Dropped error from disconnected client: {}", t.getMessage());
                return;
            }
            log.warn("Dropped error", t);
        });
    }
}
