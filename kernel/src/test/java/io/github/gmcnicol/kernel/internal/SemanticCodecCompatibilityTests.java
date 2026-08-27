package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.SubjectType;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.github.gmcnicol.kernel.semanticpack.TypedCompatibilityAdapter;
import io.github.gmcnicol.kernel.semanticpack.TypedCompatibilityRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticCodecCompatibilityTests {
    private static final SubjectType<ContactId> SUBJECT =
            new SubjectType<>("crm.ContactId", ContactId.class, ContactId::value);
    private static final ProjectionType<ContactId, ContactV1> V1 = new ProjectionType<>(
            "crm.FollowUpProjectionV1", 1, SUBJECT, ContactV1.class, List.of());
    private static final ProjectionType<ContactId, ContactV2> V2 = new ProjectionType<>(
            "crm.FollowUpProjectionV2", 2, SUBJECT, ContactV2.class, List.of());
    private static final ProjectionType<ContactId, ContactV3> V3 = new ProjectionType<>(
            "crm.FollowUpProjectionV3", 3, SUBJECT, ContactV3.class, List.of());
    private static final EventType<EventV1> EVENT_V1 = new EventType<>("crm.InteractionRecordedV1", 1, EventV1.class);
    private static final EventType<EventV2> EVENT_V2 = new EventType<>("crm.InteractionRecordedV2", 2, EventV2.class);
    private static final SemanticBindings BINDINGS = new SemanticBindings(
            List.of(V1, V2, V3), List.of(), List.of(), List.of(EVENT_V1, EVENT_V2), List.of());

    @Test
    void decodesHistoricalEvidenceThroughOneExplicitTypedAdapterChainWithoutRewritingBytes() {
        var codec = new SemanticCodec(List.of(BINDINGS), List.of(
                new TypedCompatibilityAdapter<>(V1, V2, old -> new ContactV2(old.id(), old.open(), "unknown")),
                new TypedCompatibilityAdapter<>(V2, V3, current -> new ContactV3(
                        current.id(), current.open(), current.displayName(), false))), CanonicalCodec.Limits.defaults());
        var historical = codec.encode(V1, new ContactV1(new ContactId("ada"), true));
        byte[] originalBytes = historical.canonicalUtf8();

        ContactV3 decoded = codec.decode(historical, V3);

        assertThat(decoded).isEqualTo(new ContactV3(new ContactId("ada"), true, "unknown", false));
        assertThat(historical.qualifiedType()).isEqualTo(V1.qualifiedName());
        assertThat(historical.contractVersion()).isEqualTo(1);
        assertThat(historical.canonicalUtf8()).isEqualTo(originalBytes);
    }

    @Test
    void decodesHistoricalEventEvidenceThroughCurrentActionContract() {
        var codec = new SemanticCodec(List.of(BINDINGS), List.of(
                new TypedCompatibilityAdapter<>(EVENT_V1, EVENT_V2, event -> new EventV2(event.id(), "legacy"))),
                CanonicalCodec.Limits.defaults());
        var historical = codec.encode(EVENT_V1, new EventV1(new ContactId("ada")));

        EventV2 decoded = codec.decodeEvent(historical, List.of(EVENT_V2));

        assertThat(decoded).isEqualTo(new EventV2(new ContactId("ada"), "legacy"));
    }

    @Test
    void missingAmbiguousAndCyclicCompatibilityPathsFailClosed() {
        assertThatThrownBy(() -> new SemanticCodec(List.of(BINDINGS), List.of(),
                List.of(new TypedCompatibilityRequirement<>(V1, V2)), CanonicalCodec.Limits.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing Semantic compatibility adapter");

        assertThatThrownBy(() -> new SemanticCodec(List.of(BINDINGS), List.of(
                new TypedCompatibilityAdapter<>(V1, V2, old -> new ContactV2(old.id(), old.open(), "one")),
                new TypedCompatibilityAdapter<>(V1, V3, old -> new ContactV3(old.id(), old.open(), "two", false)),
                new TypedCompatibilityAdapter<>(V3, V2, current -> new ContactV2(
                        current.id(), current.open(), current.displayName()))),
                List.of(new TypedCompatibilityRequirement<>(V1, V2)), CanonicalCodec.Limits.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous Semantic compatibility adapter chain");

        assertThatThrownBy(() -> new SemanticCodec(List.of(BINDINGS), List.of(
                new TypedCompatibilityAdapter<>(V1, V2, old -> new ContactV2(old.id(), old.open(), "one")),
                new TypedCompatibilityAdapter<>(V2, V1, current -> new ContactV1(current.id(), current.open()))),
                CanonicalCodec.Limits.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic Semantic compatibility adapter");
    }

    record ContactId(@JsonValue String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        ContactId {}
    }

    record ContactV1(ContactId id, boolean open) {}

    record ContactV2(ContactId id, boolean open, String displayName) {}

    record ContactV3(ContactId id, boolean open, String displayName, boolean archived) {}

    record EventV1(ContactId id) {}

    record EventV2(ContactId id, String note) {}
}
