package io.github.gmcnicol.taxi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lang.taxi.CompilationError;
import lang.taxi.Compiler;
import lang.taxi.CompilerConfig;
import lang.taxi.TaxiDocument;
import lang.taxi.messages.Severity;
import lang.taxi.services.ServiceMember;
import lang.taxi.sources.SourceCode;
import lang.taxi.types.ArrayType;
import lang.taxi.types.Compiled;
import lang.taxi.types.EnumType;
import lang.taxi.types.Field;
import lang.taxi.types.ObjectType;
import lang.taxi.types.PrimitiveType;
import lang.taxi.types.StreamType;
import lang.taxi.types.Type;
import lang.taxi.types.TypeAlias;
import lang.taxi.types.TypeKind;

final class TaxiJavaGenerator {
    private static final String STANDARD_SCHEMA = "/META-INF/saas-kernel/standard.taxi";
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "_", "true", "false", "null", "record", "sealed", "permits", "yield", "var");
    private static final Set<String> FORBIDDEN_RECORD_COMPONENTS = Set.of(
            "clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait");
    private static final Map<PrimitiveType, String> PRIMITIVES = Map.ofEntries(
            Map.entry(PrimitiveType.STRING, "java.lang.String"),
            Map.entry(PrimitiveType.BOOLEAN, "java.lang.Boolean"),
            Map.entry(PrimitiveType.INTEGER, "java.lang.Integer"),
            Map.entry(PrimitiveType.LONG, "java.lang.Long"),
            Map.entry(PrimitiveType.DECIMAL, "java.math.BigDecimal"),
            Map.entry(PrimitiveType.DOUBLE, "java.lang.Double"),
            Map.entry(PrimitiveType.LOCAL_DATE, "java.time.LocalDate"),
            Map.entry(PrimitiveType.TIME, "java.time.LocalTime"),
            Map.entry(PrimitiveType.DATE_TIME, "java.time.LocalDateTime"),
            Map.entry(PrimitiveType.INSTANT, "java.time.Instant"));

    private TaxiJavaGenerator() {}

    static Result generate(Path sourceDirectory, Path outputDirectory, String basePackage) throws IOException {
        validateQualifiedIdentifier(basePackage, "base package");
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("Taxi source directory does not exist: " + sourceDirectory);
        }
        var sources = readSources(sourceDirectory);
        var authoredSources = sources.stream().map(SourceCode::getSourceName).collect(java.util.stream.Collectors.toSet());
        TaxiDocument document;
        List<String> warnings;
        try (InputStream stream = TaxiJavaGenerator.class.getResourceAsStream(STANDARD_SCHEMA)) {
            if (stream == null) throw new IllegalStateException("Kernel Taxi standard schema is missing");
            sources.add(0, new SourceCode(
                    STANDARD_SCHEMA,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    Path.of(STANDARD_SCHEMA),
                    "taxi"));
            var compilation = new Compiler(sources, List.of(), new CompilerConfig()).compileWithMessages();
            var messages = compilation.getFirst();
            var errors = messages.stream().filter(message -> message.getSeverity() == Severity.ERROR).toList();
            if (!errors.isEmpty()) throw new IllegalArgumentException(format(errors));
            warnings = messages.stream()
                    .filter(message -> message.getSeverity() != Severity.ERROR)
                    .map(TaxiJavaGenerator::format)
                    .toList();
            document = compilation.getSecond();
        }

        validate(document, authoredSources);
        var generated = render(document, basePackage, authoredSources);
        replaceOutput(outputDirectory, generated);
        return new Result(warnings);
    }

    private static ArrayList<SourceCode> readSources(Path directory) throws IOException {
        var sources = new ArrayList<SourceCode>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".taxi")).sorted().toList()) {
                sources.add(new SourceCode(path.toString(), Files.readString(path), path, "taxi"));
            }
        }
        if (sources.isEmpty()) throw new IllegalArgumentException("No Taxi sources found in " + directory);
        return sources;
    }

    private static void validate(TaxiDocument document, Set<String> authoredSources) {
        var generatedNames = new HashSet<String>();
        var models = new HashMap<String, ObjectType>();
        var authoredTypes = document.getTypes().stream()
                .filter(type -> authored(type, authoredSources))
                .sorted(Comparator.comparing(Type::getQualifiedName))
                .toList();
        for (Type type : authoredTypes) {
            validateQualifiedIdentifier(type.getQualifiedName(), location(type));
            if (!generatedNames.add(type.getQualifiedName())) fail(type, "generated-name collision");
            if (type instanceof TypeAlias) fail(type, "type aliases and general-purpose unions are unsupported");
            if (type instanceof EnumType enumType) {
                if (enumType.isLenient()) fail(type, "lenient enums are unsupported");
                enumType.getValues().forEach(value -> {
                    validateIdentifier(value.getName(), location(type));
                    if (!(value.getValue() instanceof String enumValue)
                            || !enumValue.equals(value.getName())
                            || !value.getSynonyms().isEmpty()
                            || value.isDefault()) {
                        fail(type, "only simple symbolic enums are supported");
                    }
                });
            } else if (type instanceof ObjectType objectType) {
                if (objectType.getAnonymous()) fail(type, "anonymous object types are unsupported");
                if (objectType.isPartialType()) fail(type, "partial models are unsupported");
                if (objectType.getExpression() != null) fail(type, "computed expressions are unsupported");
                if (objectType.getTypeKind() == TypeKind.Type) {
                    primitive(objectType);
                } else {
                    if (objectType.getInheritsFrom().stream().anyMatch(parent -> parent != PrimitiveType.ANY)) {
                        fail(type, "model inheritance is unsupported");
                    }
                    models.put(type.getQualifiedName(), objectType);
                    for (Field field : objectType.getFields()) {
                        validateIdentifier(field.getName(), location(field));
                        if (FORBIDDEN_RECORD_COMPONENTS.contains(field.getName())) {
                            fail(field, "invalid Java record component '" + field.getName() + "'");
                        }
                        if (!field.getConstraints().isEmpty()) fail(field, "constraints are unsupported");
                        if (field.getAccessor() != null || field.getReadExpression() != null) {
                            fail(field, "computed expressions are unsupported");
                        }
                        validateFieldType(field, field.getType());
                    }
                }
            } else if (!(type instanceof PrimitiveType) && !(type instanceof ArrayType)) {
                fail(type, "unsupported Taxi construct " + type.getClass().getSimpleName());
            }
        }
        for (Type type : authoredTypes) {
            for (String name : generatedNames) {
                if (type.getQualifiedName().startsWith(name + ".")) {
                    fail(type, "generated-name collision with " + name);
                }
            }
        }
        document.getServices().stream()
                .filter(service -> service.getCompilationUnits().stream()
                        .map(unit -> unit.getSource().getSourceName())
                        .anyMatch(authoredSources::contains))
                .flatMap(service -> service.getMembers().stream())
                .forEach(TaxiJavaGenerator::validateServiceMember);
        document.getFunctions().stream()
                .filter(function -> function.getHasBody() && authored(function, authoredSources))
                .forEach(function -> fail(function, "computed expressions are unsupported"));
        document.getQueries().stream()
                .filter(query -> authored(query, authoredSources))
                .forEach(query -> fail(query, "computed expressions are unsupported"));
        document.getExpressions().stream()
                .filter(expression -> authored(expression, authoredSources))
                .forEach(expression -> fail(expression, "computed expressions are unsupported"));
        rejectRecursion(models);
    }

    private static void validateServiceMember(ServiceMember member) {
        if (member instanceof lang.taxi.services.Stream) fail(member, "streams are unsupported");
        validateServiceType(member, member.getReturnType());
        for (var parameter : member.getParameters()) {
            validateServiceType(member, parameter.getType());
            if (!parameter.getConstraints().isEmpty()) fail(member, "constraints are unsupported");
            if (parameter.getDefaultValue() != null) fail(member, "computed expressions are unsupported");
        }
    }

    private static void validateServiceType(ServiceMember member, Type type) {
        if (type instanceof StreamType) fail(member, "streams are unsupported");
        if (type instanceof ArrayType array) validateServiceType(member, array.getMemberType());
        if (type instanceof lang.taxi.types.MapType) fail(member, "maps are unsupported");
        if (type instanceof lang.taxi.types.IntersectionType) fail(member, "intersections are unsupported");
        if (type instanceof lang.taxi.types.UnionType) fail(member, "general-purpose unions are unsupported");
    }

    private static void validateFieldType(Field field, Type type) {
        if (type instanceof ArrayType array) {
            validateFieldType(field, array.getMemberType());
        } else if (type instanceof PrimitiveType primitive) {
            if (!PRIMITIVES.containsKey(primitive)) fail(field, "unsupported primitive " + primitive.getQualifiedName());
        } else if (type instanceof ObjectType objectType) {
            if (objectType.getTypeKind() == TypeKind.Type) primitive(objectType);
        } else if (!(type instanceof EnumType)) {
            fail(field, "unsupported Taxi construct " + type.getClass().getSimpleName());
        }
    }

    private static PrimitiveType primitive(ObjectType scalar) {
        var primitive = scalar.getBasePrimitive();
        if (!PRIMITIVES.containsKey(primitive)) fail(scalar, "unsupported primitive " + primitive.getQualifiedName());
        return primitive;
    }

    private static void rejectRecursion(Map<String, ObjectType> models) {
        for (ObjectType model : models.values()) {
            var visiting = new HashSet<String>();
            var visited = new HashSet<String>();
            visit(model, models, visiting, visited, new ArrayDeque<>());
        }
    }

    private static void visit(
            ObjectType model,
            Map<String, ObjectType> models,
            Set<String> visiting,
            Set<String> visited,
            ArrayDeque<String> path) {
        if (visited.contains(model.getQualifiedName())) return;
        if (!visiting.add(model.getQualifiedName())) {
            path.addLast(model.getQualifiedName());
            fail(model, "recursive models are unsupported: " + String.join(" -> ", path));
        }
        path.addLast(model.getQualifiedName());
        for (Field field : model.getFields()) {
            Type type = field.getType();
            while (type instanceof ArrayType array) type = array.getMemberType();
            if (type instanceof ObjectType child
                    && child.getTypeKind() == TypeKind.Model
                    && models.containsKey(child.getQualifiedName())) {
                visit(child, models, visiting, visited, path);
            }
        }
        path.removeLast();
        visiting.remove(model.getQualifiedName());
        visited.add(model.getQualifiedName());
    }

    private static Map<Path, String> render(TaxiDocument document, String basePackage, Set<String> authoredSources) {
        var files = new HashMap<Path, String>();
        for (Type type : document.getTypes().stream().sorted(Comparator.comparing(Type::getQualifiedName)).toList()) {
            if (!authored(type, authoredSources)) continue;
            String source = switch (type) {
                case ObjectType objectType when objectType.getTypeKind() == TypeKind.Type -> renderScalar(objectType, basePackage);
                case ObjectType objectType -> renderModel(objectType, basePackage);
                case EnumType enumType -> renderEnum(enumType, basePackage);
                default -> throw new IllegalStateException("Validated type was not renderable: " + type.getQualifiedName());
            };
            files.put(javaPath(type, basePackage), source);
        }
        return files;
    }

    private static boolean authored(Compiled compiled, Set<String> authoredSources) {
        return compiled.getCompilationUnits().stream()
                .map(unit -> unit.getSource().getSourceName())
                .anyMatch(authoredSources::contains);
    }

    private static String renderScalar(ObjectType type, String basePackage) {
        String packageName = javaPackage(type, basePackage);
        String name = simpleName(type.getQualifiedName());
        String valueType = PRIMITIVES.get(primitive(type));
        return """
                package %s;

                import com.fasterxml.jackson.annotation.JsonCreator;
                import com.fasterxml.jackson.annotation.JsonValue;
                import java.util.Objects;

                public record %s(@JsonValue %s value) {
                    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
                    public %s {
                        Objects.requireNonNull(value, "value");
                    }
                }
                """.formatted(packageName, name, valueType, name);
    }

    private static String renderModel(ObjectType type, String basePackage) {
        String packageName = javaPackage(type, basePackage);
        String name = simpleName(type.getQualifiedName());
        var fields = type.getFields();
        String components = fields.stream()
                .map(field -> "        " + javaType(field.getType(), field.getNullable(), basePackage) + " " + field.getName())
                .reduce((left, right) -> left + ",\n" + right)
                .orElse("");
        var body = new StringBuilder();
        for (Field field : fields) {
            if (field.getType() instanceof ArrayType array) {
                String checked = "java.util.Objects.requireNonNull(" + field.getName() + ", \"" + field.getName() + "\")";
                body.append("        ").append(field.getName()).append(" = ");
                if (field.getNullable()) {
                    body.append(checked).append(".map(value -> ").append(immutableList(array, "value")).append(")");
                } else {
                    body.append(immutableList(array, checked));
                }
                body.append(";\n");
            } else {
                body.append("        java.util.Objects.requireNonNull(").append(field.getName()).append(", \"")
                        .append(field.getName()).append("\");\n");
            }
        }
        return "package " + packageName + ";\n\npublic record " + name + "(\n" + components + "\n) {\n"
                + "    public " + name + " {\n" + body + "    }\n}\n";
    }

    private static String immutableList(ArrayType array, String expression) {
        if (array.getMemberType() instanceof ArrayType member) {
            return expression + ".stream().map(value -> " + immutableList(member, "value") + ").toList()";
        }
        return "java.util.List.copyOf(" + expression + ")";
    }

    private static String renderEnum(EnumType type, String basePackage) {
        String values = type.getValues().stream().map(value -> "    " + value.getName()).reduce((a, b) -> a + ",\n" + b).orElse("");
        return "package " + javaPackage(type, basePackage) + ";\n\npublic enum " + simpleName(type.getQualifiedName())
                + " {\n" + values + "\n}\n";
    }

    private static String javaType(Type type, boolean nullable, String basePackage) {
        String value;
        if (type instanceof ArrayType array) {
            value = "java.util.List<" + javaType(array.getMemberType(), false, basePackage) + ">";
        } else if (type instanceof PrimitiveType primitive) {
            value = PRIMITIVES.get(primitive);
        } else {
            value = basePackage + "." + type.getQualifiedName();
        }
        return nullable ? "java.util.Optional<" + value + ">" : value;
    }

    private static Path javaPath(Type type, String basePackage) {
        return Path.of((basePackage + "." + type.getQualifiedName()).replace('.', '/') + ".java");
    }

    private static String javaPackage(Type type, String basePackage) {
        String qualifiedName = type.getQualifiedName();
        int separator = qualifiedName.lastIndexOf('.');
        return separator < 0 ? basePackage : basePackage + "." + qualifiedName.substring(0, separator);
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private static void replaceOutput(Path output, Map<Path, String> files) throws IOException {
        Path parent = Objects.requireNonNull(output.toAbsolutePath().getParent());
        Files.createDirectories(parent);
        String suffix = UUID.randomUUID().toString();
        Path temporary = parent.resolve(output.getFileName() + ".new-" + suffix);
        Path backup = parent.resolve(output.getFileName() + ".old-" + suffix);
        Files.createDirectory(temporary);
        try {
            for (var file : files.entrySet()) {
                Path target = temporary.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
            if (Files.exists(output)) move(output, backup);
            try {
                move(temporary, output);
            } catch (IOException failure) {
                if (Files.exists(backup)) move(backup, output);
                throw failure;
            }
            deleteTree(backup);
        } finally {
            deleteTree(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void validateQualifiedIdentifier(String value, String location) {
        for (String part : value.split("\\.", -1)) validateIdentifier(part, location);
    }

    private static void validateIdentifier(String value, String location) {
        if (value.isEmpty() || JAVA_KEYWORDS.contains(value) || !Character.isJavaIdentifierStart(value.charAt(0))
                || value.codePoints().skip(1).anyMatch(character -> !Character.isJavaIdentifierPart(character))) {
            throw new IllegalArgumentException(location + ": invalid Java identifier '" + value + "'");
        }
    }

    private static void fail(Type type, String message) {
        throw new IllegalArgumentException(location(type) + ": " + message);
    }

    private static void fail(Field field, String message) {
        throw new IllegalArgumentException(location(field) + ": " + message);
    }

    private static void fail(ServiceMember member, String message) {
        fail((Compiled) member, message);
    }

    private static void fail(Compiled compiled, String message) {
        var unit = compiled.getCompilationUnits().getFirst();
        throw new IllegalArgumentException(
                unit.getSource().getSourceName() + ":" + unit.getLocation().getLine() + ":"
                        + unit.getLocation().getChar() + ": " + message);
    }

    private static String location(Type type) {
        if (type.getCompilationUnits().isEmpty()) return type.getQualifiedName();
        var unit = type.getCompilationUnits().getFirst();
        return unit.getSource().getSourceName() + ":" + unit.getLocation().getLine() + ":" + unit.getLocation().getChar();
    }

    private static String location(Field field) {
        var unit = field.getCompilationUnit();
        return unit.getSource().getSourceName() + ":" + unit.getLocation().getLine() + ":" + unit.getLocation().getChar();
    }

    private static String format(List<CompilationError> errors) {
        return errors.stream().map(TaxiJavaGenerator::format).reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }

    private static String format(CompilationError error) {
        return error.getSourceName() + ":" + error.getLine() + ":" + error.getChar() + ": " + error.getDetailMessage();
    }

    record Result(List<String> warnings) {}
}
