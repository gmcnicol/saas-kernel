package io.github.gmcnicol.kernel.internal;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;

final class FatalInvariantHandler {

    private final ConfigurableApplicationContext context;
    private final KernelTelemetry telemetry;
    private final KernelRuntimeHealth health;

    FatalInvariantHandler(
            ConfigurableApplicationContext context, KernelTelemetry telemetry, KernelRuntimeHealth health) {
        this.context = context;
        this.telemetry = telemetry;
        this.health = health;
    }

    void terminate(FatalInvariantError error) {
        health.fatal();
        telemetry.fatalInvariant();
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        Thread.ofPlatform().name("kernel-fatal-shutdown").start(context::close);
    }
}
