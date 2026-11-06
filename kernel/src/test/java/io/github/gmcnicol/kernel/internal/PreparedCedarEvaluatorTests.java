package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Regression at the owned scalar evaluator/lifetime boundary; public reads exercise its caller. */
@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class PreparedCedarEvaluatorTests {
    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    static final String SCHEMA = "entity User; entity Row { visible: Bool, blocked: Bool }; action read appliesTo { principal: User, resource: Row, context: {} };";
    static final Map<String, String> POLICIES = Map.of("permit", "permit(principal, action, resource) when { resource.visible };",
            "forbid", "forbid(principal, action, resource) when { resource.blocked };");
    @Test void packagedIdentityIncludesTheEffectivePatchAndDependencyLock() throws Exception {
        CedarNativeLibrary.load();
        String root = "/META-INF/saas-kernel/native/";
        var properties = new Properties();
        try (var metadata = getClass().getResourceAsStream(root + "build.properties");
             var patch = getClass().getResourceAsStream(root + "source/patches/prepared-condition.patch");
             var lock = getClass().getResourceAsStream(root + "Cargo.lock")) {
            properties.load(Objects.requireNonNull(metadata));
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            String patchHash = HexFormat.of().formatHex(digest.digest(Objects.requireNonNull(patch).readAllBytes()));
            assertThat(properties.getProperty("cedar.patch.sha256")).isEqualTo(patchHash);
            assertThat(properties.getProperty("cedar.source")).isEqualTo(
                    "09e21d967f1da2cf2abbe1e0b0b2067b41867735+patch.sha256:" + patchHash);
            assertThat(NativeCedar.identity()).isEqualTo(properties.getProperty("abi") + ";cedar/" + properties.getProperty("cedar.source"));
            assertThat(HexFormat.of().formatHex(digest.digest(Objects.requireNonNull(lock).readAllBytes())))
                    .isEqualTo(properties.getProperty("cargo.lock.sha256"));
        }
    }

    @Test void closedHandlesCannotBeReusedOrGrantAuthorityToAnotherAssembly() {
        var evaluator = PreparedCedarEvaluator.open(SCHEMA, POLICIES);
        assertThat(evaluator.evaluate(request(true, false), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        evaluator.close(); evaluator.close();
        assertThatThrownBy(() -> evaluator.evaluate(request(true, false), System.nanoTime() + 1_000_000_000L)).isInstanceOf(IllegalStateException.class);
        try (var other = PreparedCedarEvaluator.open(SCHEMA, POLICIES)) {
            assertThat(other.evaluate(request(true, true), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.DENY);
        }
    }
    @Test void immutableBundleSharingReleasesEveryHandleWithoutRecyclingTokens() {
        CedarNativeLibrary.load();
        String encoded = PreparedCedarEvaluator.encode(Map.of("schema", SCHEMA, "policies", POLICIES), 262_144);
        var handles = new ArrayList<Long>();
        long before = NativeCedar.statistics()[0];
        try {
            for (int i = 0; i < 128; i++) {
                long handle = NativeCedar.open(encoded);
                assertThat(handle).isBetween(1L, 0x4b75000000000000L - 1);
                handles.add(handle);
            }
            assertThat(NativeCedar.statistics()[0]).isEqualTo(before + 128);
            assertThat(NativeCedar.statistics()[1]).isEqualTo(1);
            assertThat(NativeCedar.statistics()[2]).isBetween(1L, 64L * 1024 * 1024);
            assertThat(NativeCedar.open(encoded)).isGreaterThanOrEqualTo(0x4b75000000000000L);
        } finally { handles.forEach(handle -> assertThat(NativeCedar.close(handle)).isEqualTo(1)); }
        assertThat(NativeCedar.statistics()[0]).isEqualTo(before);
        assertThat(NativeCedar.statistics()[1]).isZero();
        assertThat(NativeCedar.statistics()[12]).isZero();
        long fresh = NativeCedar.open(encoded);
        try { assertThat(fresh).isGreaterThan(handles.getLast()); }
        finally { assertThat(NativeCedar.close(fresh)).isEqualTo(1); }
    }

    @Test void tamperedResourcesUnknownAbiAndDiagnosticAllowsFailClosed() {
        assertThatThrownBy(() -> CedarNativeLibrary.verify(new byte[] {1, 2, 3}, "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Packaged Cedar native integrity check failed");
        assertThatThrownBy(() -> ReadDecision.decode(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReadDecision.decode(0x4b75000100000005L)).isInstanceOf(IllegalStateException.class);
        assertThat(ReadDecision.decode(0x4b75000100000101L)).isEqualTo(ReadDecision.INVALID); // warning with Allow
        assertThat(ReadDecision.decode(0x4b75000100010001L)).isEqualTo(ReadDecision.INVALID); // error with Allow
    }

    @Test void expiredOrInterruptedAdmissionCannotEvaluateAndRestoresCapacity() {
        try (var evaluator = PreparedCedarEvaluator.open(SCHEMA, POLICIES)) {
            long evaluated = NativeCedar.statistics()[7];
            assertThatThrownBy(() -> evaluator.evaluate(request(true, false), System.nanoTime() - 1))
                    .isInstanceOf(IllegalStateException.class);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> evaluator.evaluate(request(true, false), System.nanoTime() + 1_000_000_000L))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(NativeCedar.statistics()[7]).isEqualTo(evaluated);
            assertThat(evaluator.evaluate(request(true, false), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        }
    }

    @Test void concurrentScalarCallsNeverShareSameUidEntityState() throws Exception {
        try (var evaluator = PreparedCedarEvaluator.open(SCHEMA, POLICIES);
             var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                final boolean visible = i % 2 == 0;
                results.add(executor.submit(() -> {
                    for (int call = 0; call < 24; call++)
                        assertThat(evaluator.evaluate(request(visible, false), System.nanoTime() + 1_000_000_000L))
                                .isEqualTo(visible ? ReadDecision.ALLOW : ReadDecision.DENY);
                }));
            }
            for (var result : results) result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test void oversizedUtf8PayloadIsRejectedBeforeNativeInvocation() {
        try (var evaluator = PreparedCedarEvaluator.open(SCHEMA, POLICIES)) {
            long calls = NativeCedar.statistics()[10];
            var oversized = new HashMap<String, Object>(request(true, false));
            oversized.put("context", Map.of("payload", "雪".repeat(400_000)));
            assertThatThrownBy(() -> evaluator.evaluate(oversized, System.nanoTime() + 1_000_000_000L))
                    .isInstanceOf(RuntimeException.class);
            assertThat(NativeCedar.statistics()[10]).isEqualTo(calls);
            assertThat(evaluator.evaluate(request(true, false), System.nanoTime() + 1_000_000_000L)).isEqualTo(ReadDecision.ALLOW);
        }
    }

    static Map<String, Object> request(boolean visible, boolean blocked) {
        var principal = Map.of("type", "User", "id", "u");
        var resource = Map.of("type", "Row", "id", "same");
        return Map.of("principal", principal, "action", Map.of("type", "Action", "id", "read"), "resource", resource,
                "context", Map.of(), "entities", List.of(Map.of("uid", resource, "attrs", Map.of("visible", visible, "blocked", blocked), "parents", List.of())));
    }
}
