package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.Principal;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;

/** Deep scalar seam: codecs, native lifetime and platform choice do not escape ordinary reads. */
interface ReadPolicyEvaluator extends AutoCloseable {
    ReadDecision evaluate(Principal principal, String resourceType, String resourceId, Map<String, Value> attributes,
            String operation, Map<String, Value> context, long deadline) throws Exception;
    @Override void close();

    static ReadPolicyEvaluator create(Schema schema, PolicySet policies, String selection, MeterRegistry meters) {
        if (!Set.of("auto", "prepared", "sdk").contains(selection)) throw new IllegalArgumentException("Unknown read evaluator selection");
        boolean supported = !selection.equals("sdk") && CedarNativeLibrary.supported();
        if (selection.equals("prepared") && !supported) throw new IllegalStateException("Prepared Cedar platform unsupported");
        boolean nativeMode = supported && !selection.equals("sdk");
        String backend = nativeMode ? "prepared-scalar" : selection.equals("sdk") ? "sdk-explicit" : "sdk-unsupported-platform";
        var counter = meters.counter("kernel.read.authorization.calls", "backend", backend);
        meters.gauge("kernel.read.authorization.backend", List.of(io.micrometer.core.instrument.Tag.of("backend", backend)), 1);
        if (!nativeMode) {
            var engine = new BasicAuthorizationEngine();
            return new ReadPolicyEvaluator() {
                public ReadDecision evaluate(Principal principal, String type, String id, Map<String, Value> attributes,
                        String operation, Map<String, Value> context, long deadline) throws Exception {
                    counter.increment();
                    var actor = new Entity(uid(principal.type(), principal.id()), Map.of(), Set.of());
                    var resource = new Entity(uid(type, id), attributes, Set.of());
                    var request = new AuthorizationRequest(actor.getEUID(), uid("Action", operation), resource.getEUID(), Optional.of(context), Optional.of(schema), true);
                    var result = engine.isAuthorized(request, policies, Set.of(actor, resource));
                    if (!result.warnings.isEmpty() || result.success.isEmpty() || !result.success.get().getErrors().isEmpty()) return ReadDecision.INVALID;
                    return result.success.get().isAllowed() ? ReadDecision.ALLOW : ReadDecision.DENY;
                }
                public void close() {}
            };
        }
        var links = policies.templateLinks.stream().map(link -> {
            var slots = new TreeMap<String, Object>(); link.getLinkValues().forEach((name, entity) -> slots.put(name, uid(entity)));
            return Map.of("templateId", link.getTemplateId(), "resultPolicyId", link.getResultPolicyId(), "slots", slots);
        }).toList();
        var prepared = PreparedCedarEvaluator.open(schema.schemaText.orElseThrow(() -> new IllegalStateException("Read bundle must use authored Cedar schema")),
                policies.getStaticPolicies(), policies.getTemplates(), links);
        String[] names = { "handles", "bundles", "cache.charged.bytes", "live.bytes", "peak.bytes", "allocated.bytes", "allocations", "evaluations", "failures", "queued", "invocations", "ffi.entries", "worker.threads" };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            meters.gauge("kernel.read.native." + names[i], index, n -> {
                long[] stats = NativeCedar.statistics();
                return stats == null || stats.length != names.length ? Double.NaN : stats[n];
            });
        }
        return new ReadPolicyEvaluator() {
            public ReadDecision evaluate(Principal principal, String type, String id, Map<String, Value> attributes,
                    String operation, Map<String, Value> context, long deadline) {
                counter.increment();
                var actor = Map.of("type", principal.type(), "id", principal.id());
                var resource = Map.of("type", type, "id", id);
                var entities = List.of(Map.of("uid", actor, "attrs", Map.of(), "parents", List.of()),
                        Map.of("uid", resource, "attrs", values(attributes, 0), "parents", List.of()));
                return prepared.evaluate(Map.of("principal", actor, "action", Map.of("type", "Action", "id", operation),
                        "resource", resource, "context", values(context, 0), "entities", entities), deadline);
            }
            public void close() { prepared.close(); }
        };
    }
    private static EntityUID uid(String type, String id) {
        return new EntityUID(EntityTypeName.parse(type).orElseThrow(() -> new IllegalArgumentException("Invalid Cedar type")), id);
    }
    private static Map<String, String> uid(EntityUID uid) {
        var value = uid.asJson(); return Map.of("type", value.type, "id", value.id);
    }
    private static Map<String, Object> values(Map<String, Value> input, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Cedar value depth exceeds bound");
        var out = new LinkedHashMap<String, Object>();
        input.forEach((k, v) -> out.put(k, value(v, depth + 1)));
        return out;
    }
    private static Object value(Value input, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Cedar value depth exceeds bound");
        return switch (input) {
            case PrimBool bool -> bool.getValue();
            case PrimLong number -> number.getValue();
            case PrimString text -> text.toString();
            case CedarMap map -> values(map, depth + 1);
            case CedarList list -> list.stream().map(v -> value(v, depth + 1)).toList();
            case EntityUID entity -> Map.of("__entity", uid(entity));
            case Decimal decimal -> Map.of("__extn", Map.of("fn", "decimal", "arg", decimal.toString()));
            case IpAddress ip -> Map.of("__extn", Map.of("fn", "ip", "arg", ip.toString()));
            default -> throw new IllegalArgumentException("Unsupported complete Cedar value");
        };
    }
}
