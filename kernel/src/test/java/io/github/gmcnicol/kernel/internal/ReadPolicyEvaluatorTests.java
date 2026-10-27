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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"sdk", "prepared"})
    void throwingTelemetryCannotGrantOrDenyReads(String backend) throws Exception {
        var schema = new Schema("entity User; entity Row { visible: Bool }; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policy = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.visible };");
        var meters = new SimpleMeterRegistry() {
            @Override protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                return new io.micrometer.core.instrument.Counter() {
                    public void increment(double amount) { throw new IllegalStateException("operator telemetry unavailable"); }
                    public double count() { return 0; }
                    public io.micrometer.core.instrument.Meter.Id getId() { return id; }
                };
            }
        };
        try (var evaluator = ReadPolicyEvaluator.create(schema, policy, backend, meters)) {
            for (boolean visible : List.of(true, false)) {
                assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r",
                        Map.of("visible", new PrimBool(visible)), "read", Map.of(), System.nanoTime() + 1_000_000_000L))
                        .isEqualTo(visible ? ReadDecision.ALLOW : ReadDecision.DENY);
            }
            assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r",
                    Map.of(), "read", Map.of(), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.INVALID);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void oversizedWideAndListInputsStopBeforeMaterialisingTheTail(boolean inContext, boolean asList) throws Exception {
        var schema = new Schema("entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource);");
        Value tail = new Value() { public String toCedarExpr() { throw new AssertionError("unbounded traversal"); } };
        var text = new PrimString("雪".repeat(1024));
        var wide = new LinkedHashMap<String, Value>();
        for (int i = 0; i < 2048; i++) wide.put("item" + i, text);
        wide.put("tail", tail);
        var list = new CedarList(Collections.nCopies(2048, text));
        list.add(tail);
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, "prepared", new SimpleMeterRegistry())) {
            long calls = NativeCedar.statistics()[10];
            var input = asList ? Map.<String, Value>of("list", list) : wide;
            assertThatThrownBy(() -> evaluator.evaluate(new Principal("User", "u"), "Row", "r",
                    inContext ? Map.of() : input, "read", inContext ? input : Map.of(), System.nanoTime() + 1_000_000_000L))
                    .isInstanceOfSatisfying(ReadEvaluationLimit.class, failure ->
                            assertThat(failure.reason).isEqualTo(io.github.gmcnicol.kernel.application.CollectionReadException.Reason.BUDGET));
            assertThat(NativeCedar.statistics()[10]).isEqualTo(calls);
            assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of(), "read", Map.of(),
                    System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        }
    }

    @Test void saturatedAdmissionRejectsBeforeTraversingReadInputsAndRestoresCapacity() throws Exception {
        var schema = new Schema("entity User; entity Row { visible: Bool }; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.visible };");
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(16);
        var threads = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
        Map<String, Value> blocked = new AbstractMap<>() {
            @Override public int size() { return 1; }
            @Override public Set<Entry<String, Value>> entrySet() {
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
                } catch (InterruptedException failure) { throw new AssertionError(failure); }
                return Set.of(Map.entry("visible", new PrimBool(true)));
            }
        };
        Map<String, Value> untouched = new AbstractMap<>() {
            @Override public int size() { return 1; }
            @Override public Set<Entry<String, Value>> entrySet() { throw new AssertionError("rejected request was traversed"); }
        };
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, "prepared", new SimpleMeterRegistry());
             var executor = java.util.concurrent.Executors.newFixedThreadPool(16, task -> {
                 var thread = new Thread(task); threads.add(thread); return thread;
             })) {
            var results = new ArrayList<java.util.concurrent.Future<ReadDecision>>();
            try {
                for (int i = 0; i < 16; i++) results.add(executor.submit(() -> {
                    started.countDown();
                    return evaluator.evaluate(new Principal("User", "u"), "Row", "r", blocked, "read", Map.of(),
                            System.nanoTime() + 10_000_000_000L);
                }));
                assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                        threads.size() == 16 && threads.stream().allMatch(thread ->
                                thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING));
                long calls = NativeCedar.statistics()[10];
                assertThatThrownBy(() -> evaluator.evaluate(new Principal("User", "u"), "Row", "r", untouched,
                        "read", Map.of(), System.nanoTime() + 1_000_000_000L)).isInstanceOfSatisfying(ReadEvaluationLimit.class,
                                failure -> assertThat(failure.reason).isEqualTo(io.github.gmcnicol.kernel.application.CollectionReadException.Reason.BUDGET));
                assertThat(NativeCedar.statistics()[10]).isEqualTo(calls);
            } finally { release.countDown(); }
            for (var result : results) assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(ReadDecision.ALLOW);
            assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of("visible", new PrimBool(true)),
                    "read", Map.of(), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        } finally { release.countDown(); }
    }

    @Test void streamingRetainsCompleteCedarValuesAndExactSdkDecisions() throws Exception {
        var schema = new Schema("type Data = { owner: User, labels: Set<String>, n: Long, amount: decimal, address: ipaddr }; entity User; entity Row { data: Data }; action read appliesTo { principal: User, resource: Row, context: { data: Data } };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.data == context.data && resource.data.owner == principal && resource.data.labels.contains(\"雪\") && resource.data.n == -7 && resource.data.amount == decimal(\"12.5\") && resource.data.address == ip(\"192.0.2.1\") };");
        var data = new CedarMap(Map.of("owner", EntityUID.parse("User::\"u\"").orElseThrow(),
                "labels", new CedarList(List.of(new PrimString("雪"), new PrimString("quotes\" slash\\ newline\n"))),
                "n", new PrimLong(-7), "amount", new Decimal("12.5"), "address", new IpAddress("192.0.2.1")));
        for (String backend : List.of("sdk", "prepared")) {
            try (var evaluator = ReadPolicyEvaluator.create(schema, policies, backend, new SimpleMeterRegistry())) {
                for (boolean same : List.of(true, false)) {
                    var context = new CedarMap(data);
                    if (!same) context.put("n", new PrimLong(0));
                    assertThat(evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of("data", data), "read",
                            Map.of("data", context), System.nanoTime() + 1_000_000_000L)).isEqualTo(same ? ReadDecision.ALLOW : ReadDecision.DENY);
                }
            }
        }
    }

    @Test void cyclicListsAndMapsHitTheDepthBudgetBeforeNativeInvocation() throws Exception {
        var schema = new Schema("entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource);");
        var list = new CedarList(); list.add(list);
        var map = new CedarMap(); map.put("self", map);
        try (var evaluator = ReadPolicyEvaluator.create(schema, policies, "prepared", new SimpleMeterRegistry())) {
            long calls = NativeCedar.statistics()[10];
            for (Value value : List.of(list, map)) {
                assertThatThrownBy(() -> evaluator.evaluate(new Principal("User", "u"), "Row", "r", Map.of("value", value),
                        "read", Map.of(), System.nanoTime() + 1_000_000_000L)).isInstanceOfSatisfying(ReadEvaluationLimit.class,
                                failure -> assertThat(failure.reason).isEqualTo(io.github.gmcnicol.kernel.application.CollectionReadException.Reason.BUDGET));
            }
            assertThat(NativeCedar.statistics()[10]).isEqualTo(calls);
        }
    }

    private ReadDecision outcome(ReadPolicyEvaluator evaluator, Principal principal, Map<String, Value> attrs, String action) {
        try { return evaluator.evaluate(principal, "Row", "same", attrs, action, Map.of(), System.nanoTime() + 1_000_000_000L); }
        catch (Exception failure) { return ReadDecision.INVALID; }
    }
}
