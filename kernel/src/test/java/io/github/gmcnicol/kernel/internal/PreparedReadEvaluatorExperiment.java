package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** THROWAWAY differential measurement at the approved evaluator seam. */
class PreparedReadEvaluatorExperiment {
    @Test void decisionsAndDiagnosticsMatchSdkWithDecisionLocalSameUidGraphs() throws Exception {
        var policies = PolicySet.parsePolicies("""
                permit(principal, action, resource) when { resource.visible };
                forbid(principal, action, resource) when { resource.blocked };
                """);
        var prepared = new PreparedReadEvaluator(null, policies);
        var sdk = new BasicAuthorizationEngine();
        var q = new AuthorizationRequest(uid("User", "u"), uid("Action", "read"), uid("Row", "same"), Map.of());
        for (var attrs : List.of(Map.<String, Value>of("visible", new PrimBool(true), "blocked", new PrimBool(false)),
                Map.<String, Value>of("visible", new PrimBool(true), "blocked", new PrimBool(true)),
                Map.<String, Value>of("visible", new PrimBool(false), "blocked", new PrimBool(false)),
                Map.<String, Value>of("visible", new PrimBool(true)),
                Map.<String, Value>of("visible", new PrimString("malformed")))) {
            var entities = Set.of(new Entity(uid("Row", "same"), attrs, Set.of()));
            var expected = sdk.isAuthorized(q, policies, entities);
            var actual = prepared.evaluate(List.of(new PreparedReadEvaluator.Input(q, entities)), 0).getFirst();
            assertThat(actual.success.isPresent()).isEqualTo(expected.success.isPresent());
            assertThat(actual.warnings).isEqualTo(expected.warnings);
            assertThat(actual.success.orElseThrow().isAllowed()).isEqualTo(expected.success.orElseThrow().isAllowed());
            assertThat(actual.success.get().getErrors().size()).isEqualTo(expected.success.get().getErrors().size());
            assertThat(actual.success.get().getReason()).isEqualTo(expected.success.get().getReason());
            assertThat(canonical(actual)).isEqualTo(canonical(expected));
        }
    }
    @Test void batchDoesNotUnionSameUidGraphsAndStopsBeforeFieldsOfDeniedRecord() throws Exception {
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.visible };");
        var prepared = new PreparedReadEvaluator(null, policies);
        var q = new AuthorizationRequest(uid("User", "u"), uid("Action", "read"), uid("Row", "same"), Map.of());
        var yes = new PreparedReadEvaluator.Input(q, Set.of(new Entity(uid("Row", "same"), Map.of("visible", new PrimBool(true)), Set.of())));
        var no = new PreparedReadEvaluator.Input(q, Set.of(new Entity(uid("Row", "same"), Map.of("visible", new PrimBool(false)), Set.of())));
        assertThat(prepared.evaluate(List.of(yes, no, yes), 0).stream().map(a -> a.success.orElseThrow().isAllowed()))
                .containsExactly(true, false, true);
        assertThat(prepared.evaluate(List.of(no, yes), 1)).hasSize(1);
        assertThat(prepared.evaluate(List.of(yes, no, yes), 2)).hasSize(2);
    }

    @Test void requestValidationAndMalformedEntitiesRetainSdkFailure() throws Exception {
        var schema = new Schema("entity User; entity Row { visible: Bool }; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource);");
        var prepared = new PreparedReadEvaluator(schema, policies);
        var sdk = new BasicAuthorizationEngine();
        for (String action : List.of("read", "missing")) {
            var q = new AuthorizationRequest(uid("User", "u"), uid("Action", action), uid("Row", "r"),
                    Optional.of(Map.of()), Optional.of(schema), true);
            for (Value v : List.of(new PrimBool(true), new PrimString("bad"))) {
                var entities = Set.of(new Entity(uid("Row", "r"), Map.of("visible", v), Set.of()));
                var expected = sdk.isAuthorized(q, policies, entities);
                var actual = prepared.evaluate(List.of(new PreparedReadEvaluator.Input(q, entities)), 0).getFirst();
                assertThat(actual.type).isEqualTo(expected.type);
                assertThat(actual.warnings).isEqualTo(expected.warnings);
                assertThat(actual.errors.map(List::size)).isEqualTo(expected.errors.map(List::size));
                assertThat(canonical(actual)).isEqualTo(canonical(expected));
            }
        }
    }

    @Test void schemaWarningsAndEvaluationErrorsStopTheBatchBeforeLaterDecisions() throws Exception {
        var schema = new Schema("entity String; entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource);");
        var q = new AuthorizationRequest(uid("User", "u"), uid("Action", "read"), uid("Row", "r"), Optional.of(Map.of()), Optional.of(schema), true);
        var input = new PreparedReadEvaluator.Input(q, Set.of());
        // SDK 4.2.3 itself throws decoding structured schema warnings into List<String>.
        // Reads must fail closed for that case, not silently turn it into an Allow.
        assertThatThrownBy(() -> new BasicAuthorizationEngine().isAuthorized(q, policies, Set.of()))
                .isInstanceOf(com.cedarpolicy.model.exception.AuthException.class);
        var warningEvaluator = new PreparedReadEvaluator(schema, policies);
        assertThatThrownBy(() -> warningEvaluator.evaluate(List.of(input, input), 0)).isInstanceOf(Exception.class);
        var errorPolicies = PolicySet.parsePolicies("permit(principal, action, resource); forbid(principal, action, resource) when { resource.missing };");
        var noSchema = new AuthorizationRequest(uid("User", "u"), uid("Action", "read"), uid("Row", "r"), Map.of());
        var errorInput = new PreparedReadEvaluator.Input(noSchema, Set.of());
        var errors = new PreparedReadEvaluator(null, errorPolicies).evaluate(List.of(errorInput, errorInput), 0);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().success.orElseThrow().getErrors()).isNotEmpty();
    }

    @Test void malformedTruncatedAndReorderedNativeResultsFailClosed() throws Exception {
        String allow = "{\"type\":\"success\",\"response\":{\"decision\":\"allow\",\"diagnostics\":{\"reason\":[],\"errors\":[]}},\"warnings\":[]}";
        String first = "{\"index\":0,\"answer\":" + allow + "}";
        String second = "{\"index\":1,\"answer\":" + allow + "}";
        assertThat(PreparedReadEvaluator.validateResult("{\"results\":[" + first + "," + second + "]}", 2, 0)).hasSize(2);
        for (String raw : List.of("{}", "{", "{\"results\":[]}", "{\"results\":[" + first + "]}",
                "{\"results\":[" + second + "," + first + "]}", "{\"results\":[{\"index\":0,\"answer\":{}}]}")) {
            assertThatThrownBy(() -> PreparedReadEvaluator.validateResult(raw, 2, 0)).isInstanceOf(Exception.class);
        }
    }

    private static String canonical(Object value) throws Exception {
        var method = Class.forName("com.cedarpolicy.CedarJson").getDeclaredMethod("objectMapper");
        method.setAccessible(true);
        var mapper = (com.fasterxml.jackson.databind.ObjectMapper) method.invoke(null);
        return canonicalNode(mapper.valueToTree(value));
    }
    private static String canonicalNode(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isArray()) {
            var values = new ArrayList<String>(); node.forEach(n -> values.add(canonicalNode(n)));
            Collections.sort(values); return values.toString(); // Cedar reason/error sets have unspecified ordering.
        }
        if (node.isObject()) {
            var values = new TreeMap<String, String>(); node.fields().forEachRemaining(e -> values.put(e.getKey(), canonicalNode(e.getValue())));
            return values.toString();
        }
        return node.toString();
    }

    static EntityUID uid(String type, String id) { return new EntityUID(EntityTypeName.parse(type).orElseThrow(), id); }
}
