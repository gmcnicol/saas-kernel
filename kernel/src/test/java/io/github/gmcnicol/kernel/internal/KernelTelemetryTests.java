package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KernelTelemetryTests {

    @Test
    void telemetryFailureNeverBlocksBusinessWork() {
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStart(Observation.Context context) { throw new IllegalStateException("offline"); }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        var meters = new SimpleMeterRegistry();
        var telemetry = new KernelTelemetry(
                observations, meters, new ApplicationVersion("test.app", "1"), "1");
        var ran = new AtomicBoolean();

        String result = telemetry.observe("kernel.test", () -> {
            ran.set(true);
            return "completed";
        });
        telemetry.outcome(IntentStatus.SUCCEEDED, Duration.ofSeconds(2));
        telemetry.retry();
        telemetry.lease(true);
        telemetry.leaseLost();
        telemetry.backlogAge(Duration.ofSeconds(3));
        telemetry.reevaluation("completed", Duration.ofSeconds(4));
        telemetry.fatalInvariant();

        assertThat(result).isEqualTo("completed");
        assertThat(ran).isTrue();
        assertThat(meters.getMeters()).extracting(meter -> meter.getId().getName()).contains(
                "kernel.intent.outcomes",
                "kernel.intent.retries",
                "kernel.intent.leases",
                "kernel.intent.backlog.age",
                "kernel.intent.end.to.end",
                "kernel.reevaluation.outcomes",
                "kernel.reevaluation.backlog.age",
                "kernel.fatal.invariants");
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "worker")));
    }

    @Test
    void workerObservationIsANewRootLinkedToIncomingTrace() {
        var observations = ObservationRegistry.create();
        var stopped = new AtomicReference<Observation.Context>();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStop(Observation.Context context) {
                if (context.getName().equals("kernel.intent.attempt")) stopped.set(context);
            }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        var telemetry = new KernelTelemetry(
                observations, new SimpleMeterRegistry(), new ApplicationVersion("test.app", "1"), "1");
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        Observation parent = Observation.start("acceptance", observations);
        try (Observation.Scope ignored = parent.openScope()) {
            assertThat(telemetry.observeLinked("kernel.intent.attempt", traceparent, () -> "done"))
                    .isEqualTo("done");
        } finally {
            parent.stop();
        }

        assertThat(stopped.get().getParentObservation()).isNull();
        assertThat(stopped.get().getHighCardinalityKeyValue("trace.link").getValue())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
