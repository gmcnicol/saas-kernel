package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;

import com.cedarpolicy.model.policy.LinkValue;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.policy.TemplateLink;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class CedarLiteralBoundaryTests {
    private static final List<String> INPUTS = List.of(
            "ordinary-id", "quotes\"", "backslash\\", "line\nbreak", "雪😀", "__entity", "__extn",
            "\" || true || \"", "\" && false && \"", "x\"); permit(principal, action, resource); //");
    private static final SubjectType<String> SUBJECT = new SubjectType<>("literal.Row", String.class, s -> s);
    private static final FieldType<Row, Value> DATA = new FieldType<>("literal.View.data", Row::data, Optional::of);
    private static final ProjectionType<String, Row> VIEW =
            new ProjectionType<>("literal.View", 1, SUBJECT, Row.class, List.of(DATA));
    private static final FactType<Note> NOTE = new FactType<>("literal.Note", 1, VIEW, Note.class,
            List.of(new FieldType<Note, Value>("literal.Note.data", Note::data, Optional::of)));
    private static final TypedAuthorisationModel<Row> MODEL = new TypedAuthorisationModel<>(VIEW, Set.of(DATA), Set.of(NOTE));
    private static final String DATA_TYPE = "{ text: String, nested: { labels: Set<String> } }";

    @ParameterizedTest @ValueSource(strings = {"sdk", "prepared"})
    @org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
    void exactLiteralsSurviveCodecAndEvaluatorWithoutChangingPolicyIdentity(String backend) throws Exception {
        var schema = new Schema("type Data = " + DATA_TYPE + "; entity User; entity Row { data: Data }; "
                + "action read appliesTo { principal: User, resource: Row, context: { actor: User, row: Row, expected: Data } };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { "
                + "principal == context.actor && resource == context.row && resource.data == context.expected };");
        var original = Map.copyOf(policies.getStaticPolicies());
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
            for (String input : INPUTS) {
                var data = data(input);
                var expectedJson = Map.of("text", input, "nested", Map.of("labels", List.of(input)));
                assertThat(JsonMapper.builder().build().readValue(CedarValueCodec.encode(data, 4096), Map.class))
                        .isEqualTo(expectedJson);
                var context = Map.<String, Value>of("actor", uid("User", input), "row", uid("Row", input), "expected", data);
                assertThat(evaluator.evaluate(new Principal("User", input), "Row", input, Map.of("data", data), "read",
                        context, System.nanoTime() + 1_000_000_000L)).as(backend + ":" + input).isEqualTo(ReadDecision.ALLOW);
                var changed = new HashMap<>(context);
                changed.put("expected", data("different"));
                assertThat(evaluator.evaluate(new Principal("User", input), "Row", input, Map.of("data", data), "read",
                        changed, System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.DENY);
                assertThat(policies.getStaticPolicies()).isEqualTo(original);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "prepared"})
    @org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
    void templateLinksKeepHostileIdentifiersLiteralWithoutAddingPermitsOrDisablingForbids(String backend) throws Exception {
        var schema = new Schema("entity User; entity Row; "
                + "action read appliesTo { principal: User, resource: Row, context: {} };");
        for (String input : INPUTS) for (boolean matchingForbid : List.of(false, true)) {
            String templateId = "template-" + input;
            String linkedId = "linked-" + input;
            String source = (matchingForbid ? "forbid" : "permit")
                    + "(principal == ?principal, action == Action::\"read\", resource == ?resource);";
            var linkValues = List.of(new LinkValue("?principal", uid("User", input)),
                    new LinkValue("?resource", uid("Row", input)));
            var link = new TemplateLink(templateId, linkedId, linkValues);
            var staticPolicies = matchingForbid ? Set.of(new Policy("permit(principal, action, resource);", "permit"))
                    : Set.<Policy>of();
            var policies = new PolicySet(staticPolicies, Set.of(new Policy(source, templateId)), List.of(link));
            var originalStatic = Map.copyOf(policies.getStaticPolicies());
            try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
                assertThat(evaluator.evaluate(new Principal("User", input), "Row", input, Map.of(), "read", Map.of(),
                        System.nanoTime() + 1_000_000_000L))
                        .as("%s exact linked identities, forbid=%s, input=%s", backend, matchingForbid, input)
                        .isEqualTo(matchingForbid ? ReadDecision.DENY : ReadDecision.ALLOW);
                for (boolean otherPrincipal : List.of(false, true)) {
                    assertThat(evaluator.evaluate(new Principal("User", otherPrincipal ? input + "-different" : input),
                            "Row", otherPrincipal ? input : input + "-different", Map.of(), "read", Map.of(),
                            System.nanoTime() + 1_000_000_000L))
                            .as("%s different linked identity, forbid=%s, input=%s", backend, matchingForbid, input)
                            .isEqualTo(matchingForbid ? ReadDecision.ALLOW : ReadDecision.DENY);
                }
                assertThat(policies.getStaticPolicies()).isEqualTo(originalStatic);
                assertThat(policies.getTemplates()).containsExactlyEntriesOf(Map.of(templateId, source));
                assertThat(link.getTemplateId()).isEqualTo(templateId);
                assertThat(link.getResultPolicyId()).isEqualTo(linkedId);
                assertThat(link.getLinkValues()).containsExactlyInAnyOrderEntriesOf(
                        Map.of("?principal", uid("User", input), "?resource", uid("Row", input)));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"false", "true"})
    void projectionAndNestedFactsCannotInjectPermitsOrDisableForbids(boolean matchingForbid) throws Exception {
        var schema = new Schema("type Data = " + DATA_TYPE + "; entity User; entity Row { data: Data, Note?: { data: Data } }; "
                + "action \"typed/v1/literal.View.data\" appliesTo { principal: User, resource: Row, context: {} };");
        String source = matchingForbid
                ? "permit(principal, action, resource); forbid(principal, action, resource) when { resource has Note && resource.Note.data == resource.data };"
                : "permit(principal, action, resource) when { resource.data.text == \"approved\" };";
        var policies = PolicySet.parsePolicies(source);
        var original = Map.copyOf(policies.getStaticPolicies());
        try (var cedar = new CedarAuthoriser(schema, policies, List.of(MODEL), "literal", "c".repeat(64),
                List.of(), List.of(), Clock.systemUTC(), "sdk", new SimpleMeterRegistry())) {
            if (!matchingForbid) assertThat(cedar.allows(new Principal("User", "ordinary"), new TypedSubject<>(SUBJECT, "ordinary"),
                    VIEW, new Row(data("approved")), FactSet.empty(), DATA)).isTrue();
            for (String input : INPUTS) {
                var data = data(input);
                assertThat(cedar.allows(new Principal("User", input), new TypedSubject<>(SUBJECT, input), VIEW,
                        new Row(data), FactSet.of(Map.of(NOTE, new Note(data))), DATA)).as(input).isFalse();
                assertThat(policies.getStaticPolicies()).isEqualTo(original);
            }
        }
    }

    private static CedarMap data(String input) {
        return new CedarMap(Map.of("text", new PrimString(input),
                "nested", new CedarMap(Map.of("labels", new CedarList(List.of(new PrimString(input)))))));
    }

    private static EntityUID uid(String type, String id) {
        return new EntityUID(EntityTypeName.parse(type).orElseThrow(), id);
    }

    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    private record Row(Value data) {}
    private record Note(Value data) {}
}
