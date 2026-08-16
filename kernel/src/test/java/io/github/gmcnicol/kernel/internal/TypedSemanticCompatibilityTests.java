package io.github.gmcnicol.kernel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.CandidateType;
import io.github.gmcnicol.kernel.application.FactType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.SemanticType;
import io.github.gmcnicol.kernel.application.SubjectType;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.LegacySemanticDecoder;
import io.github.gmcnicol.kernel.semanticpack.TypedSemanticAdapter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TypedSemanticCompatibilityTests {
    private static final SubjectType<Id> SUBJECT = new SubjectType<>(
            "test.Id", Id.class, Id::value, Id::new);
    private static final ProjectionType<Id, V1> V1_TYPE = new ProjectionType<>(
            "test.v1.OldState", 1, "test.State", SUBJECT, V1.class, List.of());
    private static final ProjectionType<Id, V2> V2_TYPE = new ProjectionType<>(
            "test.v2.RenamedState", 2, "test.State", SUBJECT, V2.class, List.of());
    private static final ProjectionType<Id, V3> V3_TYPE = new ProjectionType<>(
            "test.v3.CurrentState", 3, "test.State", SUBJECT, V3.class, List.of());
    private static final SemanticBindings BINDINGS = new SemanticBindings(
            List.of(V1_TYPE, V2_TYPE, V3_TYPE), List.of());

    @Test
    void decodesTheHistoricalDescriptorThenAppliesOneChain() {
        var adaptations = new AtomicInteger();
        var compatibility = new TypedSemanticCompatibility(List.of(BINDINGS),
                List.of(TypedSemanticAdapter.of(V1_TYPE, V2_TYPE, value -> {
                            adaptations.incrementAndGet();
                            return new V2(value.name());
                        }),
                        TypedSemanticAdapter.of(V2_TYPE, V3_TYPE, value -> {
                            adaptations.incrementAndGet();
                            return new V3(value.name());
                        })),
                CanonicalCodec.Limits.defaults());
        var evidence = compatibility.encode(V1_TYPE, new V1("Alex"));

        assertEquals(new V3("Alex"), compatibility.decode(evidence, V3_TYPE));
        assertEquals(2, adaptations.get());
        assertThrows(IllegalArgumentException.class, () -> compatibility.requireCurrent(V1_TYPE));
    }

    @Test
    void rejectsMissingAmbiguousAndCyclicPathsAtStartup() {
        assertThrows(IllegalStateException.class, () -> new TypedSemanticCompatibility(
                List.of(BINDINGS), List.of(TypedSemanticAdapter.of(V1_TYPE, V2_TYPE, V2::new)),
                CanonicalCodec.Limits.defaults()));
        assertThrows(IllegalStateException.class, () -> new TypedSemanticCompatibility(
                List.of(BINDINGS), List.of(
                        TypedSemanticAdapter.of(V1_TYPE, V2_TYPE, V2::new),
                        TypedSemanticAdapter.of(V1_TYPE, V3_TYPE, V3::new)),
                CanonicalCodec.Limits.defaults()));
        assertThrows(IllegalStateException.class, () -> new TypedSemanticCompatibility(
                List.of(BINDINGS), List.of(adapter(V1_TYPE, V2_TYPE), adapter(V2_TYPE, V1_TYPE)),
                CanonicalCodec.Limits.defaults()));
    }

    @Test
    void usesAuthoredFamiliesForRenamesAndKeepsUnrelatedContractsApart() {
        var oldRequest = new CandidateType<>("sales.v1.OldRequest", 1, "sales.Request", V1.class, List.of());
        var renamedRequest = new CandidateType<>(
                "sales.v2.RenamedRequest", 2, "sales.Request", V2.class, List.of());
        var supportStatus = new CandidateType<>("support.Status", 1, "support.Status", V1.class, List.of());
        var salesStatus = new CandidateType<>("sales.Status", 1, "sales.Status", V1.class, List.of());
        var alternateProjection = new ProjectionType<>(
                "test.Alternate", 1, "test.Alternate", SUBJECT, V1.class, List.of());
        var bindings = new SemanticBindings(
                List.of(alternateProjection), List.of(),
                List.of(oldRequest, renamedRequest, supportStatus, salesStatus), List.of(), List.of());

        assertThrows(IllegalStateException.class, () -> new TypedSemanticCompatibility(
                List.of(bindings), List.of(), CanonicalCodec.Limits.defaults()));
        new TypedSemanticCompatibility(List.of(new SemanticBindings(
                List.of(V3_TYPE, alternateProjection), List.of(), List.of(supportStatus, salesStatus),
                List.of(), List.of())), List.of(), CanonicalCodec.Limits.defaults());

        var oldFact = new FactType<>("test.v1.Signal", 1, "test.Signal", V1_TYPE, V1.class, List.of());
        var wrongFact = new FactType<>(
                "test.v2.Signal", 2, "test.Signal", alternateProjection, V2.class, List.of());
        assertThrows(IllegalStateException.class, () -> new TypedSemanticCompatibility(
                List.of(new SemanticBindings(
                        List.of(V1_TYPE, alternateProjection), List.of(oldFact, wrongFact))),
                List.of(TypedSemanticAdapter.of(oldFact, wrongFact, V2::new)),
                CanonicalCodec.Limits.defaults()));
    }

    @Test
    void usesGeneratedLegacyFactoryBeforeTheSameAdapterChain() {
        var adaptations = new AtomicInteger();
        var decoder = LegacySemanticDecoder.of(
                V1_TYPE, Set.of("name"), fields -> new V1(fields.required("name", value -> value)));
        var bindings = new SemanticBindings(
                List.of(V1_TYPE, V2_TYPE, V3_TYPE), List.of(), List.of(), List.of(), List.of(), List.of(decoder));
        var compatibility = new TypedSemanticCompatibility(List.of(bindings), List.of(
                TypedSemanticAdapter.of(V1_TYPE, V2_TYPE, value -> {
                    adaptations.incrementAndGet();
                    return new V2(value);
                }),
                TypedSemanticAdapter.of(V2_TYPE, V3_TYPE, value -> new V3(value.name()))),
                CanonicalCodec.Limits.defaults());

        assertEquals(new V3("Alex"), compatibility.decodeLegacy(V1_TYPE, Map.of("name", "Alex"), V3_TYPE));
        assertEquals(new V3("Alex"), compatibility.decodeLegacyProjection(Map.of("name", "Alex"), V3_TYPE));
        assertEquals(new V3("Alex"), compatibility.decodeLegacy(
                V1_TYPE.qualifiedName(), V1_TYPE.contractVersion(), Map.of("name", "Alex")));
        assertThrows(IllegalArgumentException.class,
                () -> compatibility.decodeLegacy(V1_TYPE, Map.of("unknown", "Alex"), V3_TYPE));
        assertThrows(IllegalArgumentException.class,
                () -> compatibility.decodeLegacy("test.Unknown", 1, Map.of("name", "Alex")));
        assertThrows(IllegalArgumentException.class,
                () -> compatibility.decodeLegacy(V1_TYPE, Map.of("name", "x".repeat(65_537)), V3_TYPE));
        assertEquals(3, adaptations.get());
    }

    private static <S, T> TypedSemanticAdapter<S, T> adapter(SemanticType<S> source, SemanticType<T> target) {
        return new TypedSemanticAdapter<>() {
            @Override public SemanticType<S> source() { return source; }
            @Override public SemanticType<T> target() { return target; }
            @Override public T adapt(S value) { throw new AssertionError(); }
        };
    }

    private record Id(String value) {}
    private record V1(String name) {}
    private record V2(String name) { V2(V1 value) { this(value.name()); } }
    private record V3(String name) { V3(V1 value) { this(value.name()); } }
}
