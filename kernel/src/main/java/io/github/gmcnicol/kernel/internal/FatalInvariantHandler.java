package io.github.gmcnicol.kernel.internal;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;

final class FatalInvariantHandler {

    private final ConfigurableApplicationContext context;
    private final KernelTelemetry telemetry;

    FatalInvariantHandler(ConfigurableApplicationContext context, KernelTelemetry telemetry) {
        this.context = context;
        this.telemetry = telemetry;
    }

    void terminate(FatalInvariantError error) {
        telemetry.fatalInvariant();
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        context.close();
    }
}
