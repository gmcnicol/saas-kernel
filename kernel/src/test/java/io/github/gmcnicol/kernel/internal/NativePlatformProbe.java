package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Artifact-only platform proof: compile separately, never place reactor runtime classes on its classpath. */
public final class NativePlatformProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected supported, selection, backend, runtime SHA256");
        var runtime = Path.of(Kernel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(runtime)) throw new AssertionError("Runtime must be a packaged JAR");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime)));
        if (!digest.equals(args[3])) throw new AssertionError("Runtime artifact mismatch");
        boolean supported = CedarNativeLibrary.supported();
        if (supported != Boolean.parseBoolean(args[0])) throw new AssertionError("Unexpected prepared ABI support");
        System.out.printf("PLATFORM_PROBE os=%s arch=%s java=%s supported=%s runtime_sha256=%s%n",
                System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.runtime.version"), supported, digest);
        if (args[1].equals("gate")) {
            try { CedarNativeLibrary.load(); }
            catch (IllegalStateException rejected) {
                if (!supported && "Prepared Cedar ABI is unsupported".equals(rejected.getMessage())) {
                    System.out.println("PLATFORM_PROBE PASS unsupported-gate");
                    return;
                }
                throw rejected;
            }
            throw new AssertionError("Unsupported ABI loaded");
        }
        var schema = new Schema("entity User; entity Row { visible: Bool, blocked: Bool, overflow: Bool, owner?: User }; "
                + "action read appliesTo { principal: User, resource: Row, context: {} };");
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.visible }; "
                + "forbid(principal, action, resource) when { resource.blocked }; "
                + "forbid(principal, action, resource) when { resource.overflow && 9223372036854775807 + 1 == 0 };");
        if (!new BasicAuthorizationEngine().validate(new ValidationRequest(schema, policies)).validationPassed())
            throw new AssertionError("Probe policy must validate");
        if (args[1].equals("reject")) {
            try (var evaluator = ReadPolicyEvaluator.create(schema, policies, "prepared", new SimpleMeterRegistry())) {
                throw new AssertionError("Unsupported prepared selection succeeded");
            } catch (IllegalStateException rejected) {
                if (!supported && "Prepared Cedar platform unsupported".equals(rejected.getMessage())) {
                    System.out.println("PLATFORM_PROBE PASS unsupported-prepared");
                    return;
                }
                throw rejected;
            }
        }
        for (int cycle = 0; cycle < 3; cycle++) {
            var meters = new SimpleMeterRegistry();
            try (var evaluator = ReadPolicyEvaluator.create(schema, policies, args[1], meters)) {
                if (meters.get("kernel.read.authorization.backend").tag("backend", args[2]).gauge().value() != 1)
                    throw new AssertionError("Backend mismatch");
                expect(evaluator, attributes(true, false, false), ReadDecision.ALLOW);
                expect(evaluator, attributes(false, false, false), ReadDecision.DENY);
                expect(evaluator, attributes(true, true, false), ReadDecision.DENY);
                expect(evaluator, attributes(true, false, true), ReadDecision.INVALID);
                var typed = new HashMap<>(attributes(true, false, false));
                typed.put("owner", new EntityUID(EntityTypeName.parse("User").orElseThrow(), "u"));
                expect(evaluator, typed, ReadDecision.ALLOW);
                var confused = new HashMap<>(typed);
                confused.put("owner", new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u"))));
                expect(evaluator, confused, ReadDecision.INVALID);
            }
            if (args[2].equals("prepared-scalar")) {
                long[] statistics = NativeCedar.statistics();
                for (int index : new int[] {0, 1, 2, 9, 12})
                    if (statistics[index] != 0) throw new AssertionError("Native resources did not drain: " + index);
            }
        }
        System.out.println("PLATFORM_PROBE PASS backend=" + args[2] + " cycles=3 decisions=18");
    }

    private static Map<String, Value> attributes(boolean visible, boolean blocked, boolean overflow) {
        return Map.of("visible", new PrimBool(visible), "blocked", new PrimBool(blocked), "overflow", new PrimBool(overflow));
    }

    private static void expect(ReadPolicyEvaluator evaluator, Map<String, Value> attributes, ReadDecision expected) throws Exception {
        ReadDecision actual;
        try {
            actual = evaluator.evaluate(new Principal("User", "u"), "Row", "r", attributes, "read", Map.of(),
                    System.nanoTime() + 5_000_000_000L);
        } catch (IllegalArgumentException rejected) {
            actual = ReadDecision.INVALID;
        }
        if (actual != expected) throw new AssertionError("Decision mismatch: " + actual + " != " + expected);
    }
}
