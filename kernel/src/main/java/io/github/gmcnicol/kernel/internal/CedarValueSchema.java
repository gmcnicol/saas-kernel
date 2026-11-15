package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.*;

/** Immutable schema kinds prevent schema-guided JSON parsing from promoting Java records or strings. */
final class CedarValueSchema {
    private static final Set<String> DIRECT_TYPES = Set.of("Boolean", "Bool", "Long", "String", "Entity", "Extension", "Set", "Record");
    private static final JsonNode EMPTY_RECORD = JsonNodeFactory.instance.objectNode().put("type", "Record");
    private final JsonNode namespaces;

    CedarValueSchema(Schema schema) {
        try { namespaces = schema.toJsonFormat(); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot prepare Cedar value schema", invalid); }
    }

    record CheckedRecord(Map<String, Value> values, Type type) {}

    CheckedRecord attributes(String entityType, Map<String, Value> values) {
        var name = name(entityType, "");
        var entity = namespaces.path(name.namespace()).path("entityTypes").path(name.local());
        if (entity.isMissingNode()) throw new IllegalArgumentException("Unknown Cedar resource type");
        return new CheckedRecord(values, resolve(entity.path("shape").isMissingNode() ? EMPTY_RECORD : entity.path("shape"),
                name.namespace()));
    }

    CheckedRecord context(String operation, Map<String, Value> values) {
        var action = namespaces.path("").path("actions").path(operation);
        if (action.isMissingNode()) throw new IllegalArgumentException("Unknown Cedar action");
        var context = action.path("appliesTo").path("context");
        return new CheckedRecord(values, resolve(context.isMissingNode() ? EMPTY_RECORD : context, ""));
    }

    final class Type {
        private final String kind;
        private final JsonNode node;
        private final String namespace;

        private Type(String kind, JsonNode node, String namespace) {
            this.kind = kind; this.node = node; this.namespace = namespace;
        }

        void check(Value value) {
            boolean matches = switch (kind) {
                case "Boolean", "Bool" -> value instanceof PrimBool;
                case "Long" -> value instanceof PrimLong;
                case "String" -> value instanceof PrimString;
                case "Entity" -> value instanceof EntityUID;
                case "Extension" -> value instanceof Decimal || value instanceof IpAddress;
                case "Set" -> value instanceof CedarList;
                case "Record" -> value instanceof CedarMap;
                default -> false;
            };
            if (!matches) throw new IllegalArgumentException("Cedar value kind does not match schema");
        }

        Type field(String field) {
            var attribute = node.path("attributes").path(field);
            return attribute.isMissingNode() ? null : resolve(attribute, namespace);
        }

        Type element() { return resolve(node.path("element"), namespace); }
    }

    private Type resolve(JsonNode node, String namespace) {
        var seen = new HashSet<String>();
        while (true) {
            String kind = node.path("type").asText();
            if (DIRECT_TYPES.contains(kind)) {
                return new Type(kind, node, namespace);
            }
            String reference = kind.equals("EntityOrCommon") ? node.path("name").asText() : kind;
            var name = name(reference, namespace);
            if (!seen.add(name.namespace() + "::" + name.local())) {
                throw new IllegalArgumentException("Cyclic Cedar value type");
            }
            var common = namespaces.path(name.namespace()).path("commonTypes").path(name.local());
            if (!common.isMissingNode()) {
                node = common; namespace = name.namespace(); continue;
            }
            var entity = namespaces.path(name.namespace()).path("entityTypes").path(name.local());
            if (!entity.isMissingNode()) return new Type("Entity", node, namespace);
            if (!reference.contains("::") && !namespace.isEmpty()) {
                var rootCommon = namespaces.path("").path("commonTypes").path(reference);
                if (!rootCommon.isMissingNode()) { node = rootCommon; namespace = ""; continue; }
                if (namespaces.path("").path("entityTypes").has(reference)) return new Type("Entity", node, "");
            }
            if (!reference.contains("::") || name.namespace().equals("__cedar")) {
                String builtin = switch (name.local()) {
                    case "Bool" -> "Boolean";
                    case "Long" -> "Long";
                    case "String" -> "String";
                    case "decimal", "ipaddr", "datetime", "duration" -> "Extension";
                    default -> "";
                };
                if (!builtin.isEmpty()) return new Type(builtin, node, namespace);
            }
            throw new IllegalArgumentException("Unknown Cedar value type");
        }
    }

    private record Name(String namespace, String local) {}
    private static Name name(String qualified, String namespace) {
        int separator = qualified.lastIndexOf("::");
        return separator < 0 ? new Name(namespace, qualified)
                : new Name(qualified.substring(0, separator), qualified.substring(separator + 2));
    }
}
