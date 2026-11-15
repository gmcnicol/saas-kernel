package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;

class CedarWorkflowSizeTests {
    record State(List<String> values) {}
    record Note(List<String> values) {}

    @Test void independentlyAdmittedProjectionAndFactDoNotAcquireAnAggregateReadWireLimit() throws Exception {
        var subject = new SubjectType<>("size.Row", String.class, s -> s);
        var field = new FieldType<State, List<String>>("size.View.values", State::values,
                values -> Optional.of(strings(values)));
        var projection = new ProjectionType<>("size.View", 1, subject, State.class, List.of(field));
        var fact = new FactType<>("size.Note", 1, projection, Note.class,
                List.of(new FieldType<Note, List<String>>("size.Note.values", Note::values,
                        values -> Optional.of(strings(values)))));
        var values = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> i + "x".repeat(59_999)).toList();
        var state = new State(values);
        var note = new Note(values);
        var codec = new CanonicalCodec(List.of(projection, fact));
        assertThat(codec.encode(projection, state).canonicalUtf8().length).isLessThan(1_048_576);
        assertThat(codec.encode(fact, note).canonicalUtf8().length).isLessThan(1_048_576);
        var schema = new Schema("entity User; entity Row { values: Set<String>, Note?: { values: Set<String> } }; "
                + "action \"typed/v1/size.View.values\" appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource);");
        var model = new TypedAuthorisationModel<>(projection, Set.of(field), Set.of(fact));
        try (var cedar = new CedarAuthoriser(schema, policies, List.of(model), "size", "c".repeat(64),
                List.of(), List.of(), Clock.systemUTC(), "sdk", new SimpleMeterRegistry())) {
            assertThat(cedar.allows(new Principal("User", "u"), new TypedSubject<>(subject, "r"), projection,
                    state, FactSet.of(Map.of(fact, note)), field)).isTrue();
        }
    }

    private static CedarList strings(List<String> values) {
        return new CedarList(values.stream().<Value>map(PrimString::new).toList());
    }
}
