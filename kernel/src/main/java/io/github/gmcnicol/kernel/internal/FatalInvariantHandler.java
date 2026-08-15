package io.github.gmcnicol.kernel.internal;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;

final class FatalInvariantHandler {

    private static final System.Logger LOG = System.getLogger(FatalInvariantHandler.class.getName());
    private final ConfigurableApplicationContext context;

    FatalInvariantHandler(ConfigurableApplicationContext context) {
        this.context = context;
    }

    void terminate(FatalInvariantError error) {
        LOG.log(System.Logger.Level.ERROR, "fatal_invariant detail={0}", error.getMessage());
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        context.close();
    }
}
