package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Subject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
                observations, meters, Tracer.NOOP, new ApplicationVersion("test.app", "1"), "1",
                "01234567890123456789012345678901", "test-v1");
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
        var observed = new AtomicBoolean();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStop(Observation.Context context) {
                if (context.getName().equals("kernel.intent.attempt")) observed.set(true);
            }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        var tracer = new SimpleTracer();
        var telemetry = new KernelTelemetry(
                observations, new SimpleMeterRegistry(), tracer, new ApplicationVersion("test.app", "1"), "1",
                "01234567890123456789012345678901", "test-v1");
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        var parent = tracer.nextSpan().name("acceptance").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
            assertThat(telemetry.observeLinked("kernel.intent.attempt", traceparent, () -> "done"))
                    .isEqualTo("done");
        } finally {
            parent.end();
        }

        var worker = tracer.getSpans().stream()
                .filter(span -> span.getName().equals("kernel.intent.worker"))
                .findFirst().orElseThrow();
        assertThat(observed).isTrue();
        assertThat(worker.getParentId()).isBlank();
        assertThat(worker.getTraceId()).isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(worker.getLinks()).singleElement().satisfies(link -> {
            assertThat(link.getTraceContext().traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(link.getTraceContext().spanId()).isEqualTo("00f067aa0ba902b7");
        });
    }

    @Test
    void rollbackDropsDeferredSuccessSignal() {
        var ran = new AtomicBoolean();
        TransactionSynchronizationManager.initSynchronization();
        try {
            KernelTelemetry.afterCommit(() -> ran.set(true));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(ran).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void subjectCorrelationIsKeyedAndRotatable() {
        var first = new KernelTelemetry(
                ObservationRegistry.NOOP, new SimpleMeterRegistry(), Tracer.NOOP,
                new ApplicationVersion("test.app", "1"), "1",
                "01234567890123456789012345678901", "v1");
        var rotated = new KernelTelemetry(
                ObservationRegistry.NOOP, new SimpleMeterRegistry(), Tracer.NOOP,
                new ApplicationVersion("test.app", "1"), "1",
                "abcdefghijklmnopqrstuvwxyzABCDEF", "v2");
        var subject = new Subject("crm.Contact", "alex@example.com");

        assertThat(first.subjectCorrelation("tenant-one", subject))
                .startsWith("v1:")
                .doesNotContain(subject.id())
                .isNotEqualTo(rotated.subjectCorrelation("tenant-one", subject));
    }
}
