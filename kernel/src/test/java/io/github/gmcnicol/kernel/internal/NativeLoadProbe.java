package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.PrimBool;
import io.github.gmcnicol.kernel.application.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;

/** Fresh JVM to exercise the real loader rather than a previously loaded native module. */
public final class NativeLoadProbe {
    public static void main(String[] args) throws Exception {
        try (var evaluator = ReadPolicyEvaluator.create(new Schema(PreparedCedarEvaluatorTests.SCHEMA),
                PolicySet.parsePolicies(String.join("\n", PreparedCedarEvaluatorTests.POLICIES.values())), args[0], new SimpleMeterRegistry())) {
            System.out.println(evaluator.evaluate(new Principal("User", "u"), "Row", "r",
                    Map.of("visible", new PrimBool(true), "blocked", new PrimBool(false)), "read", Map.of(), System.nanoTime() + 1_000_000_000L));
        }
    }
}
