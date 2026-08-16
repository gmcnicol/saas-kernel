package io.github.gmcnicol.kernel.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.gmcnicol.kernel.semanticpack.TypedFactDerivation;
import io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TypedRuntimePerformanceTests {
    private static final SubjectType<ContactId> CONTACT =
            new SubjectType<>("benchmark.ContactId", ContactId.class, ContactId::value);
    private static final ProjectionType<ContactId, FollowUpProjection> FOLLOW_UP = new ProjectionType<>(
            "benchmark.FollowUpProjection", 1, CONTACT, FollowUpProjection.class, List.of());
    private static final FactType<FollowUpDue> FOLLOW_UP_DUE =
            new FactType<>("benchmark.FollowUpDue", 1, FOLLOW_UP, FollowUpDue.class);
    private static final CandidateType<InteractionCandidate> INTERACTION =
            new CandidateType<>("benchmark.InteractionCandidate", 1, InteractionCandidate.class);
    private static final EventType<InteractionRecorded> INTERACTION_RECORDED =
            new EventType<>("benchmark.InteractionRecorded", 1, InteractionRecorded.class);
    private static final ActionType<FollowUpProjection, InteractionCandidate, InteractionRecorded> RECORD =
            new ActionType<>("benchmark.FollowUps.record", FOLLOW_UP, INTERACTION, List.of(INTERACTION_RECORDED));
    private static final TypedApplicabilityPolicy<FollowUpProjection> RECORD_POLICY =
            RECORD.bindApplicability((state, derived) ->
                    !state.complete() && derived.find(FOLLOW_UP_DUE).isPresent());
    private static final TypedFactDerivation<FollowUpProjection, FollowUpDue> FOLLOW_UP_DERIVATION =
            derivation(FOLLOW_UP_DUE, (state, now) -> state.complete()
                    ? TypedFactDerivation.Result.none()
                    : now.isBefore(state.dueAt())
                            ? TypedFactDerivation.Result.later(state.dueAt())
                            : TypedFactDerivation.Result.fact(new FollowUpDue(state.contactId())));

    private static final SubjectType<FilingId> FILING =
            new SubjectType<>("benchmark.FilingId", FilingId.class, FilingId::value);
    private static final ProjectionType<FilingId, FilingProjection> FILING_PROJECTION = new ProjectionType<>(
            "benchmark.FilingProjection", 1, FILING, FilingProjection.class, List.of());
    private static final FactType<FilingDueSoon> FILING_DUE =
            new FactType<>("benchmark.FilingDueSoon", 1, FILING_PROJECTION, FilingDueSoon.class);
    private static final CandidateType<RecordsCandidate> RECORDS =
            new CandidateType<>("benchmark.RecordsCandidate", 1, RecordsCandidate.class);
    private static final EventType<RecordsReceived> RECORDS_RECEIVED =
            new EventType<>("benchmark.RecordsReceived", 1, RecordsReceived.class);
    private static final ActionType<FilingProjection, RecordsCandidate, RecordsReceived> RECEIVE =
            new ActionType<>("benchmark.Filings.receive", FILING_PROJECTION, RECORDS, List.of(RECORDS_RECEIVED));
    private static final TypedApplicabilityPolicy<FilingProjection> RECEIVE_POLICY =
            RECEIVE.bindApplicability((state, ignored) -> state.recordsOutstanding());
    private static final TypedFactDerivation<FilingProjection, FilingDueSoon> FILING_DERIVATION =
            derivation(FILING_DUE, (state, now) -> {
                Instant startsAt = state.dueAt().minus(Duration.ofDays(7));
                return now.isBefore(startsAt)
                        ? TypedFactDerivation.Result.later(startsAt)
                        : TypedFactDerivation.Result.fact(new FilingDueSoon(state.dueAt()));
            });

    private static final CanonicalCodec CODEC = new CanonicalCodec(List.of(
            FOLLOW_UP, FOLLOW_UP_DUE, INTERACTION, INTERACTION_RECORDED,
            FILING_PROJECTION, FILING_DUE, RECORDS, RECORDS_RECEIVED));
    private static final JsonMapper MAP_CODEC = JsonMapper.builder().build();
    private static volatile int sink;

    @Test
    void comparesFullTypedSemanticWorkWithBenchmarkOnlyMapBaselineWithoutTimingThresholds() throws Exception {
        Instant now = Instant.parse("2040-08-15T09:00:00Z");
        var followUp = new FollowUpProjection(new ContactId("contact-a"), now.minusSeconds(1), false);
        var filing = new FilingProjection(
                new FilingId("filing-a"), new RequestId("request-a"), new ClientReference("ACME"),
                now.plus(Duration.ofDays(2)), true, false);
        CanonicalEvidence interaction = CODEC.encode(INTERACTION, new InteractionCandidate("received"));
        CanonicalEvidence records = CODEC.encode(RECORDS, new RecordsCandidate(now));
        byte[] mapInteraction = "{\"note\":\"received\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] mapRecords = ("{\"receivedAt\":\"" + now + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        for (int count = 0; count < 2_000; count++) {
            typedFollowUp(followUp, interaction, now);
            mappedFollowUp(followUp, mapInteraction, now);
            typedFiling(filing, records, now);
            mappedFiling(filing, mapRecords, now);
        }
        Measurement followUpTyped = measure(() -> typedFollowUp(followUp, interaction, now));
        Measurement followUpMap = measure(() -> mappedFollowUp(followUp, mapInteraction, now));
        Measurement ledgerTyped = measure(() -> typedFiling(filing, records, now));
        Measurement ledgerMap = measure(() -> mappedFiling(filing, mapRecords, now));

        System.out.printf(
                "typed_runtime_benchmark follow_up typed_ns=%d typed_bytes=%d map_ns=%d map_bytes=%d%n",
                followUpTyped.nanoseconds(), followUpTyped.bytes(), followUpMap.nanoseconds(), followUpMap.bytes());
        System.out.printf(
                "typed_runtime_benchmark ledgerling typed_ns=%d typed_bytes=%d map_ns=%d map_bytes=%d%n",
                ledgerTyped.nanoseconds(), ledgerTyped.bytes(), ledgerMap.nanoseconds(), ledgerMap.bytes());
        assertThat(List.of(followUpTyped, followUpMap, ledgerTyped, ledgerMap))
                .allSatisfy(value -> assertThat(value).satisfies(
                        measured -> assertThat(measured.nanoseconds()).isPositive(),
                        measured -> assertThat(measured.bytes()).isNotNegative()));
    }

    private static int typedFollowUp(
            FollowUpProjection projection, CanonicalEvidence candidateEvidence, Instant now) {
        InteractionCandidate candidate = CODEC.decode(INTERACTION, candidateEvidence);
        FactSet facts = FOLLOW_UP_DERIVATION.derive(projection, now).value()
                .<FactSet>map(fact -> FactSet.of(Map.of(FOLLOW_UP_DUE, fact))).orElseGet(FactSet::empty);
        boolean applicable = RECORD_POLICY.isApplicable(projection, facts);
        InteractionRecorded event = new InteractionRecorded(projection.contactId(), candidate.note(), now);
        return (applicable ? 1 : 0) + CODEC.encode(INTERACTION_RECORDED, event).canonicalUtf8().length;
    }

    private static int typedFiling(FilingProjection projection, CanonicalEvidence candidateEvidence, Instant now) {
        RecordsCandidate candidate = CODEC.decode(RECORDS, candidateEvidence);
        TypedFactDerivation.Result<FilingDueSoon> derived = FILING_DERIVATION.derive(projection, now);
        FactSet facts = derived.value().<FactSet>map(value -> FactSet.of(Map.of(FILING_DUE, value)))
                .orElseGet(FactSet::empty);
        boolean applicable = RECEIVE_POLICY.isApplicable(projection, facts);
        RecordsReceived event = new RecordsReceived(projection.requestId(), candidate.receivedAt());
        return (applicable ? 1 : 0) + CODEC.encode(RECORDS_RECEIVED, event).canonicalUtf8().length;
    }

    @SuppressWarnings("unchecked")
    private static int mappedFollowUp(FollowUpProjection projection, byte[] candidateJson, Instant now) {
        Map<String, Object> candidate = MAP_CODEC.readValue(candidateJson, Map.class);
        Map<String, Object> fact = Map.of("contactId", projection.contactId().value());
        boolean applicable = !projection.complete() && fact.containsKey("contactId");
        return (applicable ? 1 : 0) + MAP_CODEC.writeValueAsBytes(Map.of(
                "contactId", projection.contactId().value(), "note", candidate.get("note"), "at", now.toString())).length;
    }

    @SuppressWarnings("unchecked")
    private static int mappedFiling(FilingProjection projection, byte[] candidateJson, Instant now) {
        Map<String, Object> candidate = MAP_CODEC.readValue(candidateJson, Map.class);
        boolean due = !now.isBefore(projection.dueAt().minus(Duration.ofDays(7)));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("requestId", projection.requestId().value());
        event.put("receivedAt", candidate.get("receivedAt"));
        return (projection.recordsOutstanding() && due ? 1 : 0) + MAP_CODEC.writeValueAsBytes(event).length;
    }

    private static Measurement measure(CheckedIntSupplier operation) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported() && !bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        long thread = Thread.currentThread().threadId();
        long bytesBefore = bean.isThreadAllocatedMemorySupported() ? bean.getThreadAllocatedBytes(thread) : 0;
        long started = System.nanoTime();
        int total = 0;
        int iterations = 10_000;
        for (int count = 0; count < iterations; count++) total += operation.getAsInt();
        long elapsed = System.nanoTime() - started;
        long bytes = bean.isThreadAllocatedMemorySupported()
                ? bean.getThreadAllocatedBytes(thread) - bytesBefore : 0;
        sink = total;
        return new Measurement(elapsed / iterations, bytes / iterations);
    }

    private static <P, F> TypedFactDerivation<P, F> derivation(
            FactType<F> type,
            java.util.function.BiFunction<P, Instant, TypedFactDerivation.Result<F>> implementation) {
        return new TypedFactDerivation<>() {
            @Override public FactType<F> factType() { return type; }
            @Override public String id() { return type.qualifiedName(); }
            @Override public Result<F> derive(P projection, Instant evaluatedAt) {
                return implementation.apply(projection, evaluatedAt);
            }
        };
    }

    private interface CheckedIntSupplier { int getAsInt() throws Exception; }
    private record Measurement(long nanoseconds, long bytes) {}

    record ContactId(@JsonValue String value) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) ContactId {} }
    record FilingId(@JsonValue String value) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) FilingId {} }
    record RequestId(@JsonValue String value) { @JsonCreator(mode = JsonCreator.Mode.DELEGATING) RequestId {} }
    record ClientReference(@JsonValue String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING) ClientReference {}
    }
    record FollowUpProjection(ContactId contactId, Instant dueAt, boolean complete) {}
    record FollowUpDue(ContactId contactId) {}
    record InteractionCandidate(String note) {}
    record InteractionRecorded(ContactId contactId, String note, Instant occurredAt) {}
    record FilingProjection(
            FilingId filingId, RequestId requestId, ClientReference clientReference, Instant dueAt,
            boolean recordsOutstanding, boolean preparationStarted) {}
    record FilingDueSoon(Instant dueAt) {}
    record RecordsCandidate(Instant receivedAt) {}
    record RecordsReceived(RequestId requestId, Instant receivedAt) {}
}
