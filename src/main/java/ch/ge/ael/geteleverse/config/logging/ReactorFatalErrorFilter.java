package ch.ge.ael.geteleverse.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Intercepte le log bruyant de {@code reactor.core.Exceptions#throwIfJvmFatal}
 * (WARN + stacktrace complète sur OOM et autres {@link VirtualMachineError})
 * et le remplace par un ERROR concis. La stacktrace n'apporte rien : le frame
 * coupable est toujours dans Reactor, pas dans notre code.
 */
public class ReactorFatalErrorFilter extends TurboFilter {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("reactor.fatal");
    private static final String TARGET_LOGGER = "reactor.core.Exceptions";
    private static final String TARGET_PREFIX = "throwIfFatal";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (logger == null || !TARGET_LOGGER.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }
        if (format == null || !format.startsWith(TARGET_PREFIX)) {
            return FilterReply.NEUTRAL;
        }
        String reason = t != null
                ? t.getClass().getSimpleName() + ": " + t.getMessage()
                : "unknown";
        LOG.error("JVM fatal error detected by Reactor: {}", reason);
        return FilterReply.DENY;
    }
}
