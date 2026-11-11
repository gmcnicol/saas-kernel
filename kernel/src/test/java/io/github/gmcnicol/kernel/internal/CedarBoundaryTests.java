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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CedarBoundaryTests {
    private static final SubjectType<String> SUBJECT = new SubjectType<>("boundary.Row", String.class, s -> s);
    private static final FieldType<Row, Value> DATA =
            new FieldType<>("boundary.View.data", Row::data, Optional::of);
    private static final ProjectionType<String, Row> VIEW =
            new ProjectionType<>("boundary.View", 1, SUBJECT, Row.class, List.of(DATA));
    private static final TypedAuthorisationModel<Row> MODEL = new TypedAuthorisationModel<>(VIEW, Set.of(DATA));

    @Test void workflowWithoutCapabilitiesRejectsAMatchingPermitWithAnErroredForbid() throws Exception {
        var schema = new Schema("entity User; entity Row { data: Bool, missing?: Bool }; "
                + "action \"typed/v1/boundary.View.data\" appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource); "
                + "forbid(principal, action, resource) when { resource.missing };");
        try (var cedar = authoriser(schema, policies)) {
            assertThat(cedar.allows(new Principal("User", "u"), new TypedSubject<>(SUBJECT, "r"),
                    VIEW, new Row(new PrimBool(true)), FactSet.empty(), DATA)).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "prepared"})
    @org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
    void mapsCannotMasqueradeAsTypedEntitiesOrExtensions(String backend) throws Exception {
        for (String key : List.of("__entity", "__extn")) {
            String type = key.equals("__entity") ? "User" : "decimal";
            Value typed = key.equals("__entity") ? uid("u") : new Decimal("1.0");
            Value payload = key.equals("__entity")
                    ? new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u")))
                    : new CedarMap(Map.of("fn", new PrimString("decimal"), "arg", new PrimString("1.0")));
            String literal = key.equals("__entity") ? "principal" : "decimal(\"1.0\")";
            for (int nesting = 0; nesting < 3; nesting++) {
                String fieldType = nesting == 0 ? type : nesting == 1 ? "{ nested: " + type + " }" : "Set<" + type + ">";
                String expression = nesting == 0 ? "resource.data == " + literal
                        : nesting == 1 ? "resource.data.nested == " + literal : "resource.data.contains(" + literal + ")";
                var schema = new Schema("entity User; entity Row { data: " + fieldType + " }; "
                        + "action read appliesTo { principal: User, resource: Row, context: {} };");
                for (String clause : List.of("when { " + expression + " }", "unless { !(" + expression + ") }")) {
                    var policies = PolicySet.parsePolicies("permit(principal, action, resource) " + clause + ";");
                    try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
                        assertThat(evaluate(evaluator, nest(typed, nesting))).as("typed control: %s %s %s", backend, key, clause)
                                .isEqualTo(ReadDecision.ALLOW);
                        assertThat(evaluate(evaluator, nest(payload, nesting)))
                                .as("implicit shape: %s %s nesting=%s", backend, key, nesting)
                                .isEqualTo(ReadDecision.INVALID);
                        for (Value body : List.of(payload, new PrimString("malformed"))) {
                            ReadDecision decision;
                            try {
                                // Construction itself rejects reserved keys on patched SDKs.
                                Value fake = nest(new CedarMap(Map.of(key, body)), nesting);
                                decision = evaluate(evaluator, DATA.toCedar(DATA.value(new Row(fake))).orElseThrow());
                            } catch (IllegalArgumentException rejected) {
                                decision = ReadDecision.INVALID;
                            }
                            assertThat(decision).as("%s %s nesting=%s %s", backend, key, nesting, clause)
                                    .isEqualTo(ReadDecision.INVALID);
                        }
                    }
                }
            }
        }
    }

    @Test void sdkWorkflowRejectsReservedShapesThroughTheApplicationConverter() throws Exception {
        var schema = new Schema("entity User; entity Row { data: User }; "
                + "action \"typed/v1/boundary.View.data\" appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.data == principal };");
        try (var cedar = authoriser(schema, policies)) {
            var principal = new Principal("User", "u");
            var subject = new TypedSubject<>(SUBJECT, "r");
            assertThat(cedar.allows(principal, subject, VIEW, new Row(uid("u")), FactSet.empty(), DATA)).isTrue();
            for (boolean explicit : List.of(true, false)) {
                try {
                    var body = new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u")));
                    var fake = explicit ? new CedarMap(Map.of("__entity", body)) : body;
                    assertThat(cedar.allows(principal, subject, VIEW, new Row(fake), FactSet.empty(), DATA)).isFalse();
                } catch (IllegalArgumentException rejectedAtConstruction) {
                    // Patched SDK admission is also an acceptable rejection boundary.
                }
            }
        }
    }

    private static Value nest(Value value, int nesting) {
        return nesting == 0 ? value : nesting == 1 ? new CedarMap(Map.of("nested", value)) : new CedarList(List.of(value));
    }

    private static ReadDecision evaluate(ReadPolicyEvaluator evaluator, Value value) throws Exception {
        try {
            return evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of("data", value),
                    "read", Map.of(), System.nanoTime() + 1_000_000_000L);
        } catch (IllegalArgumentException rejected) {
            return ReadDecision.INVALID;
        }
    }

    private static EntityUID uid(String id) {
        return new EntityUID(EntityTypeName.parse("User").orElseThrow(), id);
    }

    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }

    private static CedarAuthoriser authoriser(Schema schema, PolicySet policies) {
        return new CedarAuthoriser(schema, policies, List.of(MODEL), "boundary", "c".repeat(64),
                List.of(), List.of(), Clock.systemUTC(), "sdk", new SimpleMeterRegistry());
    }

    private record Row(Value data) {}
}
