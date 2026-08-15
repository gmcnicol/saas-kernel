package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.EvaluationSnapshot;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Subject;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class KernelTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger("io.github.gmcnicol.kernel.workflow");
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final ApplicationVersion application;
    private final String kernelVersion;

    KernelTelemetry(
            ObservationRegistry observations,
            MeterRegistry meters,
            ApplicationVersion application,
            String kernelVersion) {
        this.observations = observations;
        this.meters = meters;
        this.application = application;
        this.kernelVersion = kernelVersion;
    }

    <T> T observe(String name, Supplier<T> work) {
        return observe(name, null, false, work);
    }

    <T> T observeLinked(String name, String traceparent, Supplier<T> work) {
        return observe(name, traceparent, true, work);
    }

    void outcome(IntentStatus status, Duration endToEnd) {
        counter("kernel.intent.outcomes", "outcome", status.name().toLowerCase());
        timer("kernel.intent.end.to.end", endToEnd, "outcome", status.name().toLowerCase());
    }

    void retry() {
        counter("kernel.intent.retries", "worker", "embedded");
    }

    void lease(boolean reclaimed) {
        counter("kernel.intent.leases", "outcome", reclaimed ? "reclaimed" : "claimed", "worker", "embedded");
    }

    void leaseLost() {
        counter("kernel.intent.leases", "outcome", "lost", "worker", "embedded");
    }

    void backlogAge(Duration age) {
        summary("kernel.intent.backlog.age", age, "worker", "embedded");
    }

    void reevaluation(String outcome, Duration age) {
        counter("kernel.reevaluation.outcomes", "outcome", outcome, "worker", "embedded");
        if (age != null) summary("kernel.reevaluation.backlog.age", age, "worker", "embedded");
    }

    void fatalInvariant() {
        counter("kernel.fatal.invariants", "outcome", "terminated");
        safe(() -> LOG.atError()
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .log("fatal_invariant"));
    }

    void evaluation(EvaluationSnapshot snapshot) {
        safe(() -> LOG.atInfo()
                .addKeyValue("tenant", snapshot.tenantId())
                .addKeyValue("subject_type", snapshot.subject().type())
                .addKeyValue("subject_id", snapshot.subject().id())
                .addKeyValue("evaluation_snapshot", snapshot.id())
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", snapshot.id())
                .log("evaluation_completed"));
    }

    void actionOffer(String tenantId, Subject subject, UUID snapshotId, UUID offerId, UUID correlation) {
        safe(() -> LOG.atInfo()
                .addKeyValue("tenant", tenantId)
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_id", subject.id())
                .addKeyValue("evaluation_snapshot", snapshotId)
                .addKeyValue("action_offer", offerId)
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", correlation)
                .log("action_offer_authorised"));
    }

    void intent(
            String tenantId,
            Subject subject,
            UUID offerId,
            UUID intentId,
            IntentStatus status,
            String correlation) {
        safe(() -> LOG.atInfo()
                .addKeyValue("tenant", tenantId)
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_id", subject.id())
                .addKeyValue("action_offer", offerId)
                .addKeyValue("intent", intentId)
                .addKeyValue("intent_outcome", status.name().toLowerCase())
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", correlation)
                .log("intent_state_changed"));
    }

    void event(String tenantId, Subject subject, UUID intentId, UUID eventId, String correlation) {
        safe(() -> LOG.atInfo()
                .addKeyValue("tenant", tenantId)
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_id", subject.id())
                .addKeyValue("intent", intentId)
                .addKeyValue("event", eventId)
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", correlation)
                .log("event_recorded"));
    }

    static String traceId(String traceparent, UUID fallback) {
        return traceparent == null ? fallback.toString() : traceparent.substring(3, 35);
    }

    private <T> T observe(String name, String traceparent, boolean root, Supplier<T> work) {
        Observation observation;
        try {
            observation = Observation.createNotStarted(name, observations);
            if (root) observation.parentObservation(null);
            if (traceparent != null) observation.highCardinalityKeyValue("trace.link", traceId(traceparent, null));
            observation.start();
        } catch (RuntimeException exporterFailure) {
            return work.get();
        }
        Observation.Scope scope = null;
        try {
            try {
                scope = observation.openScope();
            } catch (RuntimeException ignored) {
                // Telemetry is never authoritative.
            }
            return work.get();
        } catch (RuntimeException | Error businessFailure) {
            safe(() -> observation.error(businessFailure));
            throw businessFailure;
        } finally {
            if (scope != null) {
                try {
                    scope.close();
                } catch (RuntimeException ignored) {
                    // Telemetry is never authoritative.
                }
            }
            safe(observation::stop);
        }
    }

    private void counter(String name, String... tags) {
        safe(() -> meters.counter(name, tags).increment());
    }

    private void timer(String name, Duration duration, String... tags) {
        safe(() -> Timer.builder(name).tags(tags).register(meters).record(nonNegative(duration)));
    }

    private void summary(String name, Duration duration, String... tags) {
        safe(() -> meters.summary(name, tags).record(nonNegative(duration).toMillis() / 1000.0));
    }

    private static Duration nonNegative(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static void safe(Runnable signal) {
        try {
            signal.run();
        } catch (RuntimeException ignored) {
            // Export and logging failures cannot affect business work.
        }
    }
}
