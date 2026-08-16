package io.github.gmcnicol.kernel.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class SemanticRegistryTests {
    private static final SubjectType<String> SUBJECT = new SubjectType<>(
            "test.Id", String.class, value -> value, value -> value);
    private static final ProjectionType<String, Projection> PROJECTION = new ProjectionType<>(
            "test.Projection", 1, SUBJECT, Projection.class, List.of());
    private static final FieldType<Candidate, String> NOTE = new FieldType<>("test.Candidate.note", Candidate::note);
    private static final FieldType<Candidate, Integer> COUNT = new FieldType<>("test.Candidate.count", Candidate::count);
    private static final FieldType<Candidate, List<String>> TAGS = new FieldType<>("test.Candidate.tags", Candidate::tags);
    private static final CandidateType<Candidate> CANDIDATE = new CandidateType<>(
            "test.Candidate", 1, Candidate.class, List.of(NOTE, COUNT, TAGS));
    private static final EventType<Event> EVENT = new EventType<>("test.Event", 1, Event.class);
    private static final ActionType<Projection, Candidate, Event> ACTION = new ActionType<>(
            "test.Actions.run", PROJECTION, CANDIDATE, List.of(EVENT));
    private static final ActionType<Projection, Candidate, Event> SECOND_ACTION = new ActionType<>(
            "test.Actions.repeat", PROJECTION, CANDIDATE, List.of(EVENT));
    private static final SemanticRegistry REGISTRY = SemanticRegistry.generated(new SemanticBindings(
                    List.of(PROJECTION), List.of(), List.of(CANDIDATE), List.of(EVENT),
                    List.of(ACTION, SECOND_ACTION)),
            List.of(SemanticRegistry.formDecoder(CANDIDATE, form -> new Candidate(
                    form.required("note", value -> value), form.required("count", Integer::valueOf),
                    form.list("tags", value -> value)))));

    @Test
    void decodesOnlyAllowlistedGeneratedJsonAndForms() {
        var json = REGISTRY.decodeJson("test.Actions.run", "test.Candidate", 1,
                "{\"note\":\"hello\",\"count\":2,\"tags\":[\"one\"]}".getBytes(StandardCharsets.UTF_8),
                Optional.empty(), Optional.empty());
        var form = REGISTRY.decodeForm("test.Actions.run", "test.Candidate", 1,
                Map.of("intentId", List.of("transport"), "note", List.of("hello"), "count", List.of("2"),
                        "tags", List.of("one")),
                Set.of("intentId"), Optional.empty(), Optional.empty());

        assertThat(json.actionType()).isSameAs(ACTION);
        assertThat(json.value()).isEqualTo(new Candidate("hello", 2, List.of("one")));
        assertThat(form.value()).isEqualTo(json.value());
        assertThat(REGISTRY.decodeJson("test.Actions.repeat", "test.Candidate", 1,
                "{\"note\":\"hello\",\"count\":2,\"tags\":[\"one\"]}".getBytes(StandardCharsets.UTF_8),
                Optional.empty(), Optional.empty()).actionType()).isSameAs(SECOND_ACTION);

        rejectsJson("unknown.Candidate", "{\"note\":\"hello\",\"count\":2,\"tags\":[]}");
        rejectsJson("test.Candidate", "{\"note\":\"one\",\"note\":\"two\",\"count\":2,\"tags\":[]}");
        rejectsJson("test.Candidate", "{\"note\":\"hello\",\"count\":2,\"tags\":[],\"unknown\":true}");
        rejectsJson("test.Candidate", "{\"note\":\"hello\",\"count\":\"two\",\"tags\":[]}");
        rejectsJson("test.Candidate", "{\"note\":\"" + "x".repeat(65_537) + "\",\"count\":2,\"tags\":[]}");
        rejectsJson("test.Candidate", "{\"note\":\"hello\",\"count\":2,\"tags\":["
                + "\"\",".repeat(10_000) + "\"\"]}");
        assertThatThrownBy(() -> REGISTRY.decodeForm("test.Actions.run", "test.Candidate", 1,
                Map.of("note", List.of("one", "two"), "count", List.of("2"), "tags", List.of("one")),
                Set.of(), Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> REGISTRY.decodeForm("test.Actions.run", "test.Candidate", 1,
                Map.of("note", List.of("one"), "count", List.of("2"),
                        "tags", java.util.Collections.nCopies(10_001, "")),
                Set.of(), Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    private static void rejectsJson(String type, String json) {
        assertThatThrownBy(() -> REGISTRY.decodeJson(
                "test.Actions.run", type, 1, json.getBytes(StandardCharsets.UTF_8), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid generated Candidate Payload");
    }

    public record Projection() {}
    public record Candidate(String note, int count, List<String> tags) {}
    public record Event() {}
}
