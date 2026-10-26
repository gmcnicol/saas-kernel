package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** THROWAWAY: opt-in Linux prototype, not an SDK extension or supported Kernel API. */
final class PreparedReadEvaluator {
    private static final int BYTES = 1_048_576;
    private static final ObjectMapper JSON = sdkCodec();
    private final long handle;
    private final Schema schema;
    record Input(AuthorizationRequest request, Set<Entity> entities) {}

    PreparedReadEvaluator(Schema schema, PolicySet policies) throws Exception {
        this.schema = schema;
        System.load(Objects.requireNonNull(System.getProperty("kernel.prototype.native")));
        var bundle = new LinkedHashMap<String, Object>();
        bundle.put("schema", schema); bundle.put("policies", policies);
        var response = JSON.readTree(call(JSON.writeValueAsString(Map.of("op", "prepare", "bundle", bundle))));
        handle = response.path("handle").asLong(0);
        if (handle < 1 || !response.path("engine").asText().equals(
                "cedar-4dea3e60201f92139141d65c4a52966e056b007d-prototype-v1")) throw new IllegalStateException("Prototype prepare failed");
    }
    List<AuthorizationResponse> evaluate(List<Input> inputs, int gates) throws Exception {
        return evaluate(inputs, gates, 5_000_000_000L);
    }
    List<AuthorizationResponse> evaluate(List<Input> inputs, int gates, long timeoutNanos) throws Exception {
        if (timeoutNanos <= 0) throw new IllegalStateException("Prototype deadline");
        if (inputs.isEmpty() || inputs.size() > 64 || gates < 0 || gates > inputs.size()) throw new IllegalArgumentException();
        long started = System.nanoTime();
        var calls = new ArrayList<Map<String, Object>>();
        for (var input : inputs) {
            var q = input.request();
            if (q.schema.orElse(null) != schema) throw new IllegalArgumentException("Prototype schema identity");
            var call = new LinkedHashMap<String, Object>();
            call.put("principal", q.principalEUID); call.put("action", q.actionEUID); call.put("resource", q.resourceEUID);
            call.put("context", q.context.orElse(Map.of())); call.put("validateRequest", q.enableRequestValidation);
            call.put("entities", input.entities()); calls.add(call);
        }
        String encoded = JSON.writeValueAsString(Map.of("op", "evaluate", "handle", handle, "calls", calls, "gates", gates, "timeoutNanos", Math.min(timeoutNanos, 5_000_000_000L)));
        PrototypeReadMetrics.encoding.add(System.nanoTime() - started);
        started = System.nanoTime();
        String raw = call(encoded);
        PrototypeReadMetrics.nativeNanos.add(System.nanoTime() - started);
        PrototypeReadMetrics.invocations.increment();
        started = System.nanoTime();
        var result = validateResult(raw, inputs.size(), gates);
        PrototypeReadMetrics.decoding.add(System.nanoTime() - started);
        PrototypeReadMetrics.checks.add(result.size());
        return result;
    }
    static List<AuthorizationResponse> validateResult(String raw, int expected, int gates) throws Exception {
        if (raw == null || raw.length() > BYTES) throw new IllegalStateException("Prototype output bound");
        var nodes = JSON.readTree(raw).path("results");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > expected) throw new IllegalStateException("Prototype result failed");
        var results = new ArrayList<AuthorizationResponse>();
        boolean stopped = false;
        for (int i = 0; i < nodes.size(); i++) {
            if (stopped || !nodes.get(i).path("index").isInt() || nodes.get(i).path("index").intValue() != i)
                throw new IllegalStateException("Prototype result order failed");
            var node = nodes.get(i).required("answer");
            if (!node.path("warnings").isArray() || !Set.of("success", "failure").contains(node.path("type").asText()))
                throw new IllegalStateException("Prototype answer malformed");
            if (node.path("type").asText().equals("success")) {
                var response = node.path("response");
                if (!Set.of("allow", "deny").contains(response.path("decision").asText())
                        || !response.path("diagnostics").path("reason").isArray()
                        || !response.path("diagnostics").path("errors").isArray()) throw new IllegalStateException("Prototype answer malformed");
            } else if (!node.path("errors").isArray() || node.path("errors").isEmpty()) throw new IllegalStateException("Prototype failure malformed");
            var answer = JSON.treeToValue(node, AuthorizationResponse.class);
            results.add(answer);
            boolean clean = answer.warnings.isEmpty() && answer.success.isPresent() && answer.success.get().getErrors().isEmpty();
            stopped = !clean || (i < gates && !answer.success.orElseThrow().isAllowed());
        }
        if (nodes.size() != expected && !stopped) throw new IllegalStateException("Prototype result truncated");
        return List.copyOf(results);
    }
    static AuthorizationResponse profileSdk(AuthorizationRequest request, PolicySet policies, Set<Entity> entities) throws Exception {
        long started = System.nanoTime();
        var wrapper = Class.forName("com.cedarpolicy.BasicAuthorizationEngine$AuthorizationRequest")
                .getDeclaredConstructor(AuthorizationRequest.class, PolicySet.class, Set.class);
        wrapper.setAccessible(true);
        String input = JSON.writeValueAsString(wrapper.newInstance(request, policies, entities));
        PrototypeReadMetrics.encoding.add(System.nanoTime() - started);
        var method = com.cedarpolicy.BasicAuthorizationEngine.class.getDeclaredMethod("callCedarJNI", String.class, String.class);
        method.setAccessible(true);
        started = System.nanoTime();
        String output = (String) method.invoke(null, "AuthorizationOperation", input);
        PrototypeReadMetrics.nativeNanos.add(System.nanoTime() - started);
        PrototypeReadMetrics.invocations.increment(); PrototypeReadMetrics.checks.increment();
        started = System.nanoTime();
        var result = JSON.readValue(JSON.readTree(output).traverse(), AuthorizationResponse.class);
        PrototypeReadMetrics.decoding.add(System.nanoTime() - started);
        return result;
    }
    private static String call(String input) {
        if (input.length() > BYTES || input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BYTES) throw new IllegalArgumentException("Prototype input bound");
        String result = invoke(input);
        if (result == null || result.length() > BYTES || result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > BYTES)
            throw new IllegalStateException("Prototype output bound");
        return result;
    }
    private static ObjectMapper sdkCodec() {
        try {
            // shortcut: SDK codec is package-private; remove reflective access before any adoption.
            var method = Class.forName("com.cedarpolicy.CedarJson").getDeclaredMethod("objectMapper");
            method.setAccessible(true); return ((ObjectMapper) method.invoke(null)).copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Prototype SDK codec unavailable"); }
    }
    private static native String invoke(String input);
}
