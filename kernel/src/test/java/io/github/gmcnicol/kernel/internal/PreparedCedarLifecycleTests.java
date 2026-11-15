package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class PreparedCedarLifecycleTests {
    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }

    @Test void admittedEncodingCannotReturnAResultAfterCloseAndReopen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var original = PreparedCedarEvaluatorTests.request(true, false);
        Map<String, Object> blocked = new AbstractMap<>() {
            @Override public int size() { return original.size(); }
            @Override public Set<Entry<String, Object>> entrySet() {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("encoder release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return original.entrySet();
            }
        };
        try (var evaluator = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES);
             var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> evaluator.evaluate(blocked, System.nanoTime() + 5_000_000_000L));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                evaluator.close();
                assertDrained();
                assertThat(result.isDone()).isFalse();
                try (var replacement = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES)) {
                    release.countDown();
                    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                            .hasCauseInstanceOf(IllegalStateException.class);
                    assertThat(replacement.evaluate(PreparedCedarEvaluatorTests.request(false, false),
                            System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.DENY);
                }
            } finally { release.countDown(); }
        }
        assertDrained();
    }

    @Test void completedNativeAllowCannotEscapeAfterCloseBeforeJavaReturn() throws Exception {
        var decoded = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var evaluator = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES);
             var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                try (var decoder = org.mockito.Mockito.mockStatic(ReadDecision.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                    decoder.when(() -> ReadDecision.decode(0x4b75000100000001L)).thenAnswer(invocation -> {
                        Object real = invocation.callRealMethod();
                        assertThat(real).isEqualTo(ReadDecision.ALLOW);
                        decoded.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("decoder release timed out");
                        return real;
                    });
                    return evaluator.evaluate(PreparedCedarEvaluatorTests.request(true, false), System.nanoTime() + 5_000_000_000L);
                }
            });
            try {
                assertThat(decoded.await(5, TimeUnit.SECONDS)).isTrue();
                evaluator.close();
                assertDrained();
                release.countDown();
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasRootCauseMessage("Cedar bundle closed during evaluation");
            } finally { release.countDown(); }
        }
        assertDrained();
    }

    @Test void repeatedOpenEvaluateAndConcurrentDoubleCloseConserveQuiescentNativeResources() throws Exception {
        try (var warm = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES)) {
            assertThat(warm.evaluate(PreparedCedarEvaluatorTests.request(true, false),
                    System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        }
        assertDrained();
        long beforeCallers = NativeCedar.statistics()[3];
        try (var executor = Executors.newFixedThreadPool(4)) {
            var ready = new CountDownLatch(4);
            var serial = new Semaphore(1);
            var warmed = new ArrayList<Future<?>>();
            for (int caller = 0; caller < 4; caller++) warmed.add(executor.submit(() -> {
                ready.countDown();
                if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("caller warmup timed out");
                serial.acquire();
                try (var warm = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES)) {
                    assertThat(warm.evaluate(PreparedCedarEvaluatorTests.request(true, false),
                            System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
                } finally { serial.release(); }
                return null;
            }));
            for (var warm : warmed) warm.get(5, TimeUnit.SECONDS);
            // Rust lazily allocates caller-thread metadata; warm every JNI closer before checking retained state.
            long live = NativeCedar.statistics()[3];
            for (int cycle = 0; cycle < 64; cycle++) {
                var evaluator = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES);
                try {
                    assertThat(evaluator.evaluate(PreparedCedarEvaluatorTests.request(true, false),
                            System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
                    var closes = new ArrayList<Future<?>>();
                    for (int caller = 0; caller < 4; caller++) closes.add(executor.submit(evaluator::close));
                    for (var close : closes) close.get(5, TimeUnit.SECONDS);
                } finally { evaluator.close(); }
                assertDrained();
                assertThat(NativeCedar.statistics()[3]).as("requested live bytes after cycle %s", cycle).isEqualTo(live);
            }
        }
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(NativeCedar.statistics()[3]).as("requested live bytes after caller shutdown").isEqualTo(beforeCallers));
    }

    @Test void failedEncodingAndNativePreparationRestoreAdmissionAndDrainThePool() {
        String oversized = "x".repeat(300_000);
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThatThrownBy(() -> PreparedCedarEvaluator.open(oversized, PreparedCedarEvaluatorTests.POLICIES))
                    .isInstanceOf(ReadEvaluationLimit.class);
            assertThatThrownBy(() -> PreparedCedarEvaluator.open("not a Cedar schema", PreparedCedarEvaluatorTests.POLICIES))
                    .isInstanceOf(IllegalStateException.class);
            assertDrained();
        }
        try (var healthy = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA, PreparedCedarEvaluatorTests.POLICIES)) {
            assertThat(healthy.evaluate(PreparedCedarEvaluatorTests.request(true, false),
                    System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        }
        assertDrained();
    }

    @Test void distinctBundleLimitRejectsAndRecoversWithoutReusingOtherHandles() {
        var evaluators = new ArrayList<PreparedCedarEvaluator>();
        try {
            for (int index = 0; index < 32; index++) {
                evaluators.add(PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA,
                        Map.of("permit-" + index, "permit(principal, action, resource);")));
            }
            long[] full = NativeCedar.statistics();
            assertThat(full[0]).isEqualTo(32);
            assertThat(full[1]).isEqualTo(32);
            assertThat(full[2]).isBetween(1L, 64L * 1024 * 1024);
            assertThatThrownBy(() -> PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA,
                    Map.of("extra", "permit(principal, action, resource);"))).isInstanceOf(IllegalStateException.class);
            evaluators.removeFirst().close();
            try (var replacement = PreparedCedarEvaluator.open(PreparedCedarEvaluatorTests.SCHEMA,
                    Map.of("replacement", "permit(principal, action, resource);"))) {
                assertThat(NativeCedar.statistics()[1]).isEqualTo(32);
                assertThat(replacement.evaluate(PreparedCedarEvaluatorTests.request(true, false),
                        System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
            }
        } finally { evaluators.forEach(PreparedCedarEvaluator::close); }
        assertDrained();
    }

    private static void assertDrained() {
        long[] statistics = NativeCedar.statistics();
        for (int index : new int[] {0, 1, 2, 9, 12})
            assertThat(statistics[index]).as("quiescent native resource index %s", index).isZero();
    }

    @Test void failedNativeGaugeRegistrationClosesTheUnreturnedPreparedHandle() throws Exception {
        CedarNativeLibrary.load();
        long[] before = NativeCedar.statistics();
        var meters = new SimpleMeterRegistry() {
            @Override protected <T> Gauge newGauge(Meter.Id id, T object, ToDoubleFunction<T> value) {
                if (id.getName().startsWith("kernel.read.native."))
                    throw new IllegalStateException("synthetic gauge registration failure");
                return super.newGauge(id, object, value);
            }
        };
        var schema = new Schema(PreparedCedarEvaluatorTests.SCHEMA);
        var policies = PolicySet.parsePolicies(String.join("\n", PreparedCedarEvaluatorTests.POLICIES.values()));

        assertThatThrownBy(() -> ReadPolicyEvaluator.create(schema, policies, "prepared", meters))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic gauge registration failure");

        long[] after = NativeCedar.statistics();
        for (int index : new int[] {0, 1, 2, 9, 12})
            assertThat(after[index]).as("native resource index %s", index).isEqualTo(before[index]);
    }
}
