package io.github.gmcnicol.kernel.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

record SemanticIndex(
        String kernelVersion,
        String generatorVersion,
        String taxiCompilerVersion,
        Source standardSchema,
        List<String> dependencies,
        List<Source> sources,
        List<TypeEntry> types,
        List<ActionEntry> actions,
        List<Slot> slots,
        List<String> generatedContent) {

    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");

    static SemanticIndex parse(String content) {
        List<String> lines = content.lines().toList();
        if (lines.isEmpty() || !lines.getLast().startsWith("index-checksum=")) {
            throw invalid("index-checksum must be the final entry");
        }
        var values = new HashMap<String, String>();
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1) throw invalid("malformed entry: " + line);
            String key = line.substring(0, separator);
            if (values.putIfAbsent(key, line.substring(separator + 1)) != null) {
                throw invalid("duplicate entry: " + key);
            }
        }
        Set<String> fixed = Set.of(
                "format-version", "kernel-version", "generator-version", "taxi-compiler-version",
                "standard-schema", "index-checksum");
        values.keySet().stream()
                .filter(key -> !fixed.contains(key)
                        && !key.matches("(?:dependency|source|type|action|slot|generated-content)\\.\\d+"))
                .findFirst().ifPresent(key -> { throw invalid("unknown entry: " + key); });
        require(values, "format-version", "1");
        String canonical = String.join("\n", lines.subList(0, lines.size() - 1)) + "\n";
        String expected = required(values, "index-checksum");
        if (!CHECKSUM.matcher(expected).matches() || !sha256(canonical).equals(expected)) {
            throw invalid("checksum mismatch");
        }
        Source standard = source(required(values, "standard-schema"), "standard-schema");
        return new SemanticIndex(
                required(values, "kernel-version"),
                required(values, "generator-version"),
                required(values, "taxi-compiler-version"),
                standard,
                numbered(values, "dependency"),
                numbered(values, "source").stream().map(value -> source(value, "source")).toList(),
                numbered(values, "type").stream().map(SemanticIndex::type).toList(),
                numbered(values, "action").stream().map(SemanticIndex::action).toList(),
                numbered(values, "slot").stream().map(SemanticIndex::slot).toList(),
                numbered(values, "generated-content"));
    }

    private static List<String> numbered(Map<String, String> values, String prefix) {
        var result = new ArrayList<String>();
        for (int index = 0; ; index++) {
            String value = values.get(prefix + "." + index);
            if (value == null) break;
            result.add(value);
        }
        long count = values.keySet().stream().filter(key -> key.startsWith(prefix + ".")).count();
        if (count != result.size()) throw invalid(prefix + " entries must be contiguous from zero");
        return List.copyOf(result);
    }

    private static Source source(String value, String name) {
        String[] parts = parts(value, 2, name);
        if (!CHECKSUM.matcher(parts[1]).matches()) throw invalid(name + " has malformed checksum: " + parts[0]);
        return new Source(parts[0], parts[1]);
    }

    private static TypeEntry type(String value) {
        String[] parts = parts(value, 6, "type");
        int version;
        try {
            version = Integer.parseInt(parts[2]);
        } catch (NumberFormatException exception) {
            throw invalid("type has malformed version: " + parts[0]);
        }
        return new TypeEntry(parts[0], parts[1], version, parts[3], parts[4], parts[5]);
    }

    private static ActionEntry action(String value) {
        String[] parts = parts(value, 4, "action");
        return new ActionEntry(parts[0], parts[1], parts[2], split(parts[3]));
    }

    private static Slot slot(String value) {
        String[] parts = parts(value, 2, "slot");
        try {
            return new Slot(SlotKind.valueOf(parts[0]), parts[1]);
        } catch (IllegalArgumentException exception) {
            throw invalid("unknown implementation slot: " + parts[0]);
        }
    }

    private static String[] parts(String value, int count, String name) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != count || parts[0].isBlank()) throw invalid("malformed " + name + " entry: " + value);
        return parts;
    }

    private static List<String> split(String value) {
        return value.isEmpty() ? List.of() : List.of(value.split(",", -1));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw invalid("missing entry: " + key);
        return value;
    }

    private static void require(Map<String, String> values, String key, String expected) {
        String value = required(values, key);
        if (!expected.equals(value)) throw invalid("unsupported " + key + ": " + value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Malformed Semantic Index: " + message);
    }

    record Source(String path, String checksum) {}
    record TypeEntry(
            String qualifiedName, String role, int version,
            String javaBinding, String relationship, String shape) {}
    record ActionEntry(String qualifiedName, String projection, String candidate, List<String> events) {
        ActionEntry { events = List.copyOf(events); }
    }
    record Slot(SlotKind kind, String target) {}
    enum SlotKind { DERIVATION, APPLICABILITY, HANDLER, PROJECTOR }
}
