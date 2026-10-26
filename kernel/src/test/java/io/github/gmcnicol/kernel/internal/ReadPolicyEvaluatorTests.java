package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class ReadPolicyEvaluatorTests {
    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    @Test void scalarDecisionsAndStrictDiagnosticsMatchSdkForExactDecisionLocalValues() throws Exception {
        var schema = new Schema("entity User; entity Row { visible: Bool, blocked: Bool, label?: String }; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policy = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.visible }; forbid(principal, action, resource) when { resource.blocked };");
        try (var sdk = ReadPolicyEvaluator.create(schema, policy, "sdk", new SimpleMeterRegistry());
             var prepared = ReadPolicyEvaluator.create(schema, policy, "prepared", new SimpleMeterRegistry())) {
            for (var attrs : List.of(
                    Map.<String, Value>of("visible", new PrimBool(true), "blocked", new PrimBool(false)),
                    Map.<String, Value>of("visible", new PrimBool(false), "blocked", new PrimBool(false)),
                    Map.<String, Value>of("visible", new PrimBool(true), "blocked", new PrimBool(true)),
                    Map.<String, Value>of("visible", new PrimBool(true)),
                    Map.<String, Value>of("visible", new PrimString("malformed"), "blocked", new PrimBool(false)),
                    Map.<String, Value>of("visible", new PrimBool(true), "blocked", new PrimBool(false), "label", new PrimString("quotes\" slash\\ newline\n雪")))) {
                // Repeated identical UID, different graphs. No decision or entity state may survive a call.
                for (String action : List.of("read", "missing")) for (String type : List.of("User", "MissingPrincipalType")) {
                    var principal = new Principal(type, "u");
                    assertThat(outcome(prepared, principal, attrs, action)).isEqualTo(outcome(sdk, principal, attrs, action));
                }
            }
        }
    }
    @Test void schemaWarningsAndErroredPermitsNeverBecomeCleanAllows() throws Exception {
        for (String warningEntity : List.of("", "entity String;")) {
            var schema = new Schema(warningEntity + " entity User; entity Row { missing?: Bool }; action read appliesTo { principal: User, resource: Row, context: {} };");
            var policy = PolicySet.parsePolicies("permit(principal, action, resource); forbid(principal, action, resource) when { resource.missing };");
            try (var sdk = ReadPolicyEvaluator.create(schema, policy, "sdk", new SimpleMeterRegistry());
                 var prepared = ReadPolicyEvaluator.create(schema, policy, "prepared", new SimpleMeterRegistry())) {
                assertThat(outcome(prepared, new Principal("User", "u"), Map.of(), "read")).isEqualTo(ReadDecision.INVALID)
                        .isEqualTo(outcome(sdk, new Principal("User", "u"), Map.of(), "read"));
            }
        }
    }
    @Test void schemaWarningAloneInvalidatesAnOtherwiseCleanPermit() throws Exception {
        var schema = new Schema("entity String; entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource);");
        try (var sdk = ReadPolicyEvaluator.create(schema, policies, "sdk", new SimpleMeterRegistry());
             var prepared = ReadPolicyEvaluator.create(schema, policies, "prepared", new SimpleMeterRegistry())) {
            assertThat(outcome(prepared, new Principal("User", "u"), Map.of(), "read")).isEqualTo(ReadDecision.INVALID)
                    .isEqualTo(outcome(sdk, new Principal("User", "u"), Map.of(), "read"));
        }
    }

    private ReadDecision outcome(ReadPolicyEvaluator evaluator, Principal principal, Map<String, Value> attrs, String action) {
        try { return evaluator.evaluate(principal, "Row", "same", attrs, action, Map.of(), System.nanoTime() + 1_000_000_000L); }
        catch (Exception failure) { return ReadDecision.INVALID; }
    }
}
