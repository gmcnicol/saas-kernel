package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.EvaluationSnapshot;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.application.TypedEvaluationSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class KernelTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger("io.github.gmcnicol.kernel.workflow");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final ApplicationVersion application;
    private final String kernelVersion;
    private final byte[] subjectKey;
    private final String subjectKeyId;

    KernelTelemetry(
            ObservationRegistry observations,
            MeterRegistry meters,
            Tracer tracer,
            ApplicationVersion application,
            String kernelVersion,
            String subjectKey,
            String subjectKeyId) {
        if (subjectKey == null || subjectKey.getBytes(StandardCharsets.UTF_8).length < 32
                || subjectKeyId == null || !KEY_ID.matcher(subjectKeyId).matches()) {
            throw new IllegalArgumentException("Telemetry subject key needs 32 bytes and a safe key ID");
        }
        this.observations = observations;
        this.meters = meters;
        this.tracer = tracer;
        this.application = application;
        this.kernelVersion = kernelVersion;
        this.subjectKey = subjectKey.getBytes(StandardCharsets.UTF_8);
        this.subjectKeyId = subjectKeyId;
    }

    <T> T observe(String name, Supplier<T> work) {
        return observe(name, false, work);
    }

    <T> T observeLinked(String name, String traceparent, Supplier<T> work) {
        Span span;
        try {
            var builder = tracer.spanBuilder().setNoParent().name("kernel.intent.worker");
            if (traceparent != null) builder.addLink(new Link(traceContext(traceparent)));
            span = builder.start();
        } catch (RuntimeException exporterFailure) {
            return observe(name, true, work);
        }
        if (span.isNoop()) {
            safe(span::end);
            return observe(name, true, work);
        }
        Tracer.SpanInScope scope = null;
        try {
            try {
                scope = tracer.withSpan(span);
            } catch (RuntimeException ignored) {
                // Telemetry is never authoritative.
            }
            return observe(name, false, work);
        } catch (RuntimeException | Error businessFailure) {
            safe(() -> span.error(businessFailure));
            throw businessFailure;
        } finally {
            if (scope != null) safe(scope::close);
            safe(span::end);
        }
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
        afterCommit(() -> LOG.atInfo()
                .addKeyValue("tenant", snapshot.tenantId())
                .addKeyValue("subject_type", snapshot.subject().type())
                .addKeyValue("subject_correlation", subjectCorrelation(snapshot.tenantId(), snapshot.subject()))
                .addKeyValue("evaluation_snapshot", snapshot.id())
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", snapshot.id())
                .log("evaluation_completed"));
    }

    void evaluation(TypedEvaluationSnapshot<?, ?> snapshot) {
        Subject subject = new Subject(snapshot.subject().type().qualifiedName(), snapshot.subject().externalId());
        afterCommit(() -> LOG.atInfo()
                .addKeyValue("tenant", snapshot.tenantId())
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_correlation", subjectCorrelation(snapshot.tenantId(), subject))
                .addKeyValue("evaluation_snapshot", snapshot.id())
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", snapshot.id())
                .log("evaluation_completed"));
    }

    void actionOffer(String tenantId, Subject subject, UUID snapshotId, UUID offerId, UUID correlation) {
        afterCommit(() -> LOG.atInfo()
                .addKeyValue("tenant", tenantId)
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_correlation", subjectCorrelation(tenantId, subject))
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
        afterCommit(() -> LOG.atInfo()
                .addKeyValue("tenant", tenantId)
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_correlation", subjectCorrelation(tenantId, subject))
                .addKeyValue("action_offer", offerId)
                .addKeyValue("intent", intentId)
                .addKeyValue("intent_outcome", status.name().toLowerCase())
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", correlation)
                .log("intent_state_changed"));
    }

    void event(String tenantId, Subject subject, UUID intentId, UUID eventId, String correlation) {
        afterCommit(() -> LOG.atInfo()
                .addKeyValue("tenant", tenantId)
                .addKeyValue("subject_type", subject.type())
                .addKeyValue("subject_correlation", subjectCorrelation(tenantId, subject))
                .addKeyValue("intent", intentId)
                .addKeyValue("event", eventId)
                .addKeyValue("application_version", application.version())
                .addKeyValue("kernel_version", kernelVersion)
                .addKeyValue("trace_correlation", correlation)
                .log("event_committed"));
    }

    static String traceId(String traceparent, UUID fallback) {
        return traceparent == null ? fallback.toString() : traceparent.substring(3, 35);
    }

    private <T> T observe(String name, boolean root, Supplier<T> work) {
        Observation observation;
        try {
            observation = Observation.createNotStarted(name, observations);
            if (root) observation.parentObservation(null);
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

    private io.micrometer.tracing.TraceContext traceContext(String traceparent) {
        return tracer.traceContextBuilder()
                .traceId(traceparent.substring(3, 35))
                .spanId(traceparent.substring(36, 52))
                .sampled((Integer.parseInt(traceparent.substring(53, 55), 16) & 1) == 1)
                .build();
    }

    String subjectCorrelation(String tenantId, Subject subject) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(subjectKey, "HmacSHA256"));
            byte[] value = (tenantId + "\0" + subject.type() + "\0" + subject.id())
                    .getBytes(StandardCharsets.UTF_8);
            return subjectKeyId + ":" + HexFormat.of().formatHex(hmac.doFinal(value));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", impossible);
        }
    }

    static void afterCommit(Runnable signal) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safe(signal);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                safe(signal);
            }
        });
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
