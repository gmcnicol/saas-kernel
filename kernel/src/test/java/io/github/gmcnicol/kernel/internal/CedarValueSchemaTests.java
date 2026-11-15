package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class CedarValueSchemaTests {
    @ParameterizedTest @ValueSource(strings = {"sdk", "prepared"})
    void ordinaryRecordsMayUseEntityAndExtensionFieldNames(String backend) throws Exception {
        var schema = new Schema("type Data = { type?: String, id?: String, fn?: String, arg?: String }; "
                + "entity User; entity Row { data: Data }; "
                + "action read appliesTo { principal: User, resource: Row, context: { expected: Data } };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.data == context.expected };");
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
            for (var data : List.of(
                    new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u"))),
                    new CedarMap(Map.of("fn", new PrimString("decimal"), "arg", new PrimString("1.0"))))) {
                assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of("data", data), "read",
                        Map.of("expected", data), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "prepared"})
    void namespacedAndRootAliasesPreserveNestedRecordsAndTypedReferences(String backend) throws Exception {
        var schema = new Schema("type RootRecord = { type: String, id: String }; "
                + "namespace N { type Text = __cedar::String; type Data = { label: Text, record: RootRecord, refs: Set<User> }; "
                + "entity User; entity Row { data: Data }; } "
                + "action read appliesTo { principal: N::User, resource: N::Row, context: { expected: N::Data } };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { "
                + "resource.data == context.expected && resource.data.refs.contains(principal) };");
        var user = new EntityUID(EntityTypeName.parse("N::User").orElseThrow(), "u");
        var record = new CedarMap(Map.of("type", new PrimString("ordinary"), "id", new PrimString("__entity")));
        var data = new CedarMap(Map.of("label", new PrimString("雪"), "record", record, "refs", new CedarList(List.of(user))));
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
            assertThat(evaluator.evaluate(new Principal("N::User", "u"), "N::Row", "r", Map.of("data", data), "read",
                    Map.of("expected", data), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
            var confused = new CedarMap(data);
            confused.put("refs", new CedarList(List.of(new CedarMap(Map.of("type", new PrimString("N::User"), "id", new PrimString("u"))))));
            assertThatThrownBy(() -> evaluator.evaluate(new Principal("N::User", "u"), "N::Row", "r", Map.of("data", confused), "read",
                    Map.of("expected", data), System.nanoTime() + 1_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"sdk", "prepared"})
    void contextValuesCannotUseImplicitEntityOrExtensionCoercion(String backend) throws Exception {
        var schema = new Schema("entity User; entity Row; action read appliesTo { principal: User, resource: Row, "
                + "context: { owner: User, amount: decimal } };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { "
                + "context.owner == principal && context.amount == decimal(\"1.0\") };");
        var owner = new EntityUID(EntityTypeName.parse("User").orElseThrow(), "u");
        var context = Map.<String, Value>of("owner", owner, "amount", new Decimal("1.0"));
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
            assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of(), "read", context,
                    System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
            for (var bad : List.of(
                    Map.<String, Value>of("owner", new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u"))), "amount", new Decimal("1.0")),
                    Map.<String, Value>of("owner", owner, "amount", new CedarMap(Map.of("fn", new PrimString("decimal"), "arg", new PrimString("1.0")))),
                    Map.<String, Value>of("owner", owner, "amount", new PrimString("1.0")))) {
                assertThatThrownBy(() -> evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of(), "read", bad,
                        System.nanoTime() + 1_000_000_000L)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
}
