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
import lang.taxi.services.Operation;
import lang.taxi.services.Service;
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
import lang.taxi.types.UnionType;
import lang.taxi.expressions.TypeExpression;

final class TaxiJavaGenerator {
    private static final String STANDARD_SCHEMA = "/META-INF/saas-kernel/standard.taxi";
    private static final String SUBJECT = "io.github.gmcnicol.kernel.taxi.Subject";
    private static final String CONTRACT = "io.github.gmcnicol.kernel.taxi.Contract";
    private static final String PROJECTED_STATE = "io.github.gmcnicol.kernel.taxi.ProjectedState";
    private static final String FACT = "io.github.gmcnicol.kernel.taxi.Fact";
    private static final String EVENT = "io.github.gmcnicol.kernel.taxi.Event";
    private static final String ACTION_SERVICE = "io.github.gmcnicol.kernel.taxi.ActionService";
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
        var allAuthoredTypes = document.getTypes().stream()
                .filter(type -> authored(type, authoredSources))
                .sorted(Comparator.comparing(Type::getQualifiedName))
                .toList();
        var aliasedUnions = allAuthoredTypes.stream().filter(ObjectType.class::isInstance)
                .map(ObjectType.class::cast).filter(TaxiJavaGenerator::isEventUnion)
                .map(type -> ((TypeExpression) type.getExpression()).getType().getQualifiedName())
                .collect(java.util.stream.Collectors.toSet());
        var authoredTypes = allAuthoredTypes.stream()
                .filter(type -> !(type instanceof UnionType) || !aliasedUnions.contains(type.getQualifiedName()))
                .toList();
        var actionServices = document.getServices().stream()
                .filter(service -> authored(service, authoredSources) && serviceAnnotated(service, ACTION_SERVICE))
                .toList();
        var actionUnionNames = actionServices.stream().flatMap(service -> service.getOperations().stream())
                .map(Operation::getReturnType)
                .filter(ArrayType.class::isInstance)
                .map(ArrayType.class::cast)
                .map(ArrayType::getMemberType)
                .filter(type -> type instanceof ObjectType model && isEventUnion(model))
                .map(Type::getQualifiedName)
                .collect(java.util.stream.Collectors.toSet());
        boolean bindingsGenerated = authoredTypes.stream()
                .anyMatch(type -> annotated(type, PROJECTED_STATE) || annotated(type, FACT))
                || !actionServices.isEmpty();
        if (bindingsGenerated) {
            for (String generated : actionServices.isEmpty()
                    ? List.of("GeneratedSemanticBindings")
                    : List.of("GeneratedSemanticBindings", "GeneratedSemanticRegistry")) {
                authoredTypes.stream()
                        .filter(type -> type.getQualifiedName().equals(generated)
                                || type.getQualifiedName().startsWith(generated + "."))
                        .findFirst()
                        .ifPresent(type -> fail(type, "generated-name collision with " + generated));
                actionServices.stream()
                        .filter(service -> service.getQualifiedName().equals(generated)
                                || service.getQualifiedName().startsWith(generated + "."))
                        .findFirst()
                        .ifPresent(service -> fail(service, "generated-name collision with " + generated));
            }
        }
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
                if (isEventUnion(objectType)) {
                    if (!actionUnionNames.contains(type.getQualifiedName())
                            || !objectType.getModifiers().contains(lang.taxi.types.Modifier.CLOSED)) {
                        fail(type, "only closed Event unions used by an @ActionService are supported");
                    }
                    eventModels(objectType).forEach(event -> {
                        if (!annotated(event, EVENT)) {
                            fail(objectType, "closed Event union members must be @Event models");
                        }
                        contractVersion(event);
                    });
                    continue;
                }
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
                    var generatedMembers = new HashSet<String>();
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
                        if (!generatedMembers.add(constantName(field.getName()))) {
                            fail(field, "generated field-descriptor collision for '" + constantName(field.getName()) + "'");
                        }
                    }
                    if ((annotated(type, PROJECTED_STATE) || annotated(type, FACT) || annotated(type, EVENT))
                            && generatedMembers.contains("TYPE")) {
                        fail(type, "generated field-descriptor collision for 'TYPE'");
                    }
                    if (annotated(type, FACT) && generatedMembers.contains("DERIVATION")) {
                        fail(type, "generated field-descriptor collision for 'DERIVATION'");
                    }
                }
            } else if (!(type instanceof PrimitiveType) && !(type instanceof ArrayType)) {
                fail(type, "unsupported Taxi construct " + type.getClass().getSimpleName());
            }
        }
        for (Service service : actionServices) {
            if (!generatedNames.add(service.getQualifiedName())) fail(service, "generated-name collision");
        }
        for (Type type : authoredTypes) {
            for (String name : generatedNames) {
                if (!type.getQualifiedName().equals(name) && type.getQualifiedName().startsWith(name + ".")) {
                    fail(type, "generated-name collision with " + name);
                }
            }
        }
        for (Service service : actionServices) {
            for (String name : generatedNames) {
                if (!service.getQualifiedName().equals(name)
                        && service.getQualifiedName().startsWith(name + ".")) {
                    fail(service, "generated-name collision with " + name);
                }
            }
        }
        document.getServices().stream()
                .filter(service -> service.getCompilationUnits().stream()
                        .map(unit -> unit.getSource().getSourceName())
                        .anyMatch(authoredSources::contains))
                .forEach(service -> service.getMembers().forEach(
                        member -> validateServiceMember(member, serviceAnnotated(service, ACTION_SERVICE))));
        document.getFunctions().stream()
                .filter(function -> function.getHasBody() && authored(function, authoredSources))
                .forEach(function -> fail(function, "computed expressions are unsupported"));
        document.getQueries().stream()
                .filter(query -> authored(query, authoredSources))
                .forEach(query -> fail(query, "computed expressions are unsupported"));
        document.getExpressions().stream()
                .filter(expression -> authored(expression, authoredSources))
                .forEach(expression -> fail(expression, "computed expressions are unsupported"));
        validateRoles(document, authoredTypes, authoredSources);
        rejectRecursion(models);
    }

    private static void validateRoles(TaxiDocument document, List<Type> authoredTypes, Set<String> authoredSources) {
        var projections = new HashMap<String, ObjectType>();
        for (Type type : authoredTypes) {
            boolean subject = annotated(type, SUBJECT);
            boolean projection = annotated(type, PROJECTED_STATE);
            boolean fact = annotated(type, FACT);
            boolean event = annotated(type, EVENT);
            if ((subject ? 1 : 0) + (projection ? 1 : 0) + (fact ? 1 : 0) + (event ? 1 : 0) > 1) {
                fail(type, "semantic role annotations cannot be combined");
            }
            if (subject && (!(type instanceof ObjectType objectType)
                    || objectType.getTypeKind() != TypeKind.Type)) {
                fail(type, "@Subject may only annotate a named scalar");
            }
            if (projection) {
                if (!(type instanceof ObjectType objectType) || objectType.getTypeKind() != TypeKind.Model) {
                    fail(type, "@ProjectedState may only annotate a model");
                }
                contractVersion(type);
                String subjectName = annotationString(type, PROJECTED_STATE, "subject");
                Type subjectType;
                try {
                    subjectType = document.type(subjectName);
                } catch (RuntimeException exception) {
                    fail(type, "@ProjectedState subject must reference one @Subject scalar: " + subjectName);
                    return;
                }
                if (!(subjectType instanceof ObjectType scalar)
                        || scalar.getTypeKind() != TypeKind.Type
                        || !annotated(subjectType, SUBJECT)) {
                    fail(type, "@ProjectedState subject must reference one @Subject scalar: " + subjectName);
                }
                ObjectType previous = projections.putIfAbsent(subjectName, (ObjectType) type);
                if (previous != null) {
                    fail(type, "only one @ProjectedState is allowed for @Subject " + subjectName);
                }
            }
            if (fact) {
                if (!(type instanceof ObjectType objectType) || objectType.getTypeKind() != TypeKind.Model) {
                    fail(type, "@Fact may only annotate a model");
                }
                contractVersion(type);
                String projectionName = annotationString(type, FACT, "projection");
                Type projectionType;
                try {
                    projectionType = document.type(projectionName);
                } catch (RuntimeException exception) {
                    fail(type, "@Fact projection must reference one @ProjectedState model: " + projectionName);
                    return;
                }
                if (!(projectionType instanceof ObjectType model)
                        || model.getTypeKind() != TypeKind.Model
                        || !annotated(projectionType, PROJECTED_STATE)) {
                    fail(type, "@Fact projection must reference one @ProjectedState model: " + projectionName);
                }
            }
            if (event) {
                if (!(type instanceof ObjectType objectType) || objectType.getTypeKind() != TypeKind.Model) {
                    fail(type, "@Event may only annotate a model");
                }
                contractVersion(type);
            }
        }
        List<Service> actionServices = document.getServices().stream()
                .filter(service -> authored(service, authoredSources))
                .filter(service -> serviceAnnotated(service, ACTION_SERVICE))
                .toList();
        actionServices.forEach(service -> validateActionService(document, service));
        var eventProjections = new HashMap<String, String>();
        for (Service service : actionServices) {
            String projection = serviceAnnotationString(service, ACTION_SERVICE, "projection");
            for (Operation operation : service.getOperations()) {
                Type event = ((ArrayType) operation.getReturnType()).getMemberType();
                for (ObjectType eventModel : eventModels(event)) {
                    String previous = eventProjections.putIfAbsent(eventModel.getQualifiedName(), projection);
                    if (previous != null && !previous.equals(projection)) {
                        fail(operation, "Event " + eventModel.getQualifiedName()
                                + " is already owned by Projection " + previous);
                    }
                }
            }
        }
    }

    private static void validateActionService(TaxiDocument document, Service service) {
        validateQualifiedIdentifier(service.getQualifiedName(), location(service));
        String projectionName = serviceAnnotationString(service, ACTION_SERVICE, "projection");
        Type projection;
        try {
            projection = document.type(projectionName);
        } catch (RuntimeException exception) {
            fail(service, "@ActionService projection must reference one @ProjectedState model: " + projectionName);
            return;
        }
        if (!(projection instanceof ObjectType model) || !annotated(model, PROJECTED_STATE)) {
            fail(service, "@ActionService projection must reference one @ProjectedState model: " + projectionName);
        }
        if (service.getOperations().isEmpty()) fail(service, "@ActionService requires at least one operation");
        var generatedMembers = new HashSet<String>();
        for (Operation operation : service.getOperations()) {
            validateIdentifier(operation.getName(), location(operation));
            if (!generatedMembers.add(constantName(operation.getName()))) {
                fail(operation, "generated Action-descriptor collision for '" + constantName(operation.getName()) + "'");
            }
            if (operation.getParameters().size() != 1) {
                fail(operation, "Action requires exactly one named Candidate Payload model");
            }
            Type input = operation.getParameters().getFirst().getType();
            if (!(input instanceof ObjectType)
                    || ((ObjectType) input).getTypeKind() != TypeKind.Model
                    || ((ObjectType) input).getAnonymous()) {
                fail(operation, "Action requires exactly one named Candidate Payload model");
            }
            ObjectType candidate = (ObjectType) input;
            if (annotated(candidate, SUBJECT) || annotated(candidate, PROJECTED_STATE)
                    || annotated(candidate, FACT) || annotated(candidate, EVENT)) {
                fail(operation, "Candidate Payload model cannot carry another semantic role");
            }
            if (candidate.getFields().stream().map(field -> constantName(field.getName())).anyMatch("TYPE"::equals)) {
                fail(candidate, "generated field-descriptor collision for 'TYPE'");
            }
            contractVersion(candidate);
            if (!(operation.getReturnType() instanceof ArrayType)) {
                fail(operation, "Action must return a non-empty Event array");
            }
            Type event = ((ArrayType) operation.getReturnType()).getMemberType();
            if (!(event instanceof ObjectType)) {
                fail(operation, "Action return must contain one @Event model or closed Event union");
            }
            for (ObjectType eventModel : eventModels(event)) {
                if (!annotated(eventModel, EVENT)) {
                    fail(operation, "Action return must contain one @Event model or closed Event union");
                }
                contractVersion(eventModel);
            }
        }
    }

    private static List<ObjectType> eventModels(Type type) {
        if (type instanceof ObjectType model && isEventUnion(model)) {
            return eventModels(((TypeExpression) model.getExpression()).getType());
        }
        if (type instanceof ObjectType model) return List.of(model);
        if (type instanceof UnionType union && union.getTypes().stream().allMatch(ObjectType.class::isInstance)) {
            return union.getTypes().stream().map(ObjectType.class::cast).toList();
        }
        fail(type, "closed Event union members must be named models");
        return List.of();
    }

    private static boolean isEventUnion(ObjectType model) {
        return model.getExpression() instanceof TypeExpression expression
                && expression.getType() instanceof UnionType;
    }

    private static boolean serviceAnnotated(Service service, String qualifiedName) {
        return service.getAnnotations().stream().anyMatch(annotation -> annotation.getQualifiedName().equals(qualifiedName));
    }

    private static String serviceAnnotationString(Service service, String annotation, String parameter) {
        Object value = service.getAnnotations().stream()
                .filter(candidate -> candidate.getQualifiedName().equals(annotation))
                .findFirst().orElseThrow().parameter(parameter);
        if (!(value instanceof String text) || text.isBlank()) {
            fail(service, "@" + simpleName(annotation) + " requires " + parameter + " as a qualified type name");
        }
        return (String) value;
    }

    private static boolean annotated(Type type, String qualifiedName) {
        return type instanceof lang.taxi.types.Annotatable annotatable
                && annotatable.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getQualifiedName().equals(qualifiedName));
    }

    private static lang.taxi.types.Annotation annotation(Type type, String qualifiedName) {
        return ((lang.taxi.types.Annotatable) type).getAnnotations().stream()
                .filter(candidate -> candidate.getQualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow();
    }

    private static String annotationString(Type type, String annotation, String parameter) {
        Object value = annotation(type, annotation).parameter(parameter);
        if (!(value instanceof String text) || text.isBlank()) {
            fail(type, "@" + simpleName(annotation) + " requires " + parameter + " as a qualified type name");
        }
        return (String) value;
    }

    private static int contractVersion(Type type) {
        if (!annotated(type, CONTRACT)) fail(type, "durable semantic types require @Contract(version = n)");
        Object value = annotation(type, CONTRACT).parameter("version");
        if (!(value instanceof Number number) || number.intValue() < 1) {
            fail(type, "@Contract version must be a positive integer");
        }
        return ((Number) value).intValue();
    }

    private static void validateServiceMember(ServiceMember member, boolean actionService) {
        if (member instanceof lang.taxi.services.Stream) fail(member, "streams are unsupported");
        validateServiceType(member, member.getReturnType(), actionService);
        for (var parameter : member.getParameters()) {
            validateServiceType(member, parameter.getType(), actionService);
            if (!parameter.getConstraints().isEmpty()) fail(member, "constraints are unsupported");
            if (parameter.getDefaultValue() != null) fail(member, "computed expressions are unsupported");
        }
    }

    private static void validateServiceType(ServiceMember member, Type type, boolean actionService) {
        if (type instanceof StreamType) fail(member, "streams are unsupported");
        if (type instanceof ArrayType array) validateServiceType(member, array.getMemberType(), actionService);
        if (type instanceof lang.taxi.types.MapType) fail(member, "maps are unsupported");
        if (type instanceof lang.taxi.types.IntersectionType) fail(member, "intersections are unsupported");
        if ((type instanceof UnionType || type instanceof ObjectType model && isEventUnion(model)) && !actionService) {
            fail(member, "general-purpose unions are unsupported");
        }
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
        var allAuthoredTypes = document.getTypes().stream()
                .filter(type -> authored(type, authoredSources))
                .sorted(Comparator.comparing(Type::getQualifiedName))
                .toList();
        var aliasedUnions = allAuthoredTypes.stream().filter(ObjectType.class::isInstance)
                .map(ObjectType.class::cast).filter(TaxiJavaGenerator::isEventUnion)
                .map(type -> ((TypeExpression) type.getExpression()).getType().getQualifiedName())
                .collect(java.util.stream.Collectors.toSet());
        var authoredTypes = allAuthoredTypes.stream()
                .filter(type -> !(type instanceof UnionType) || !aliasedUnions.contains(type.getQualifiedName()))
                .toList();
        var actionServices = document.getServices().stream()
                .filter(service -> authored(service, authoredSources) && serviceAnnotated(service, ACTION_SERVICE))
                .sorted(Comparator.comparing(Service::getQualifiedName))
                .toList();
        var candidateNames = actionServices.stream().flatMap(service -> service.getOperations().stream())
                .map(operation -> operation.getParameters().getFirst().getType().getQualifiedName())
                .collect(java.util.stream.Collectors.toSet());
        var actionUnions = actionServices.stream().flatMap(service -> service.getOperations().stream())
                .map(Operation::getReturnType).map(ArrayType.class::cast).map(ArrayType::getMemberType)
                .filter(ObjectType.class::isInstance).map(ObjectType.class::cast)
                .filter(TaxiJavaGenerator::isEventUnion).distinct().toList();
        var eventInterfaces = new HashMap<String, List<String>>();
        for (ObjectType union : actionUnions) {
            for (ObjectType event : eventModels(union)) {
                eventInterfaces.compute(event.getQualifiedName(), (name, unions) -> {
                    var result = unions == null ? new ArrayList<String>() : new ArrayList<>(unions);
                    result.add(union.getQualifiedName());
                    return List.copyOf(result);
                });
            }
        }
        for (Type type : authoredTypes) {
            String source = switch (type) {
                case ObjectType objectType when isEventUnion(objectType) -> renderUnion(objectType, basePackage);
                case ObjectType objectType when objectType.getTypeKind() == TypeKind.Type -> renderScalar(objectType, basePackage);
                case ObjectType objectType -> renderModel(
                        objectType, basePackage, candidateNames.contains(type.getQualifiedName()),
                        eventInterfaces.getOrDefault(type.getQualifiedName(), List.of()));
                case EnumType enumType -> renderEnum(enumType, basePackage);
                default -> throw new IllegalStateException("Validated type was not renderable: " + type.getQualifiedName());
            };
            files.put(javaPath(type, basePackage), source);
        }
        for (Service service : actionServices) {
            files.put(javaPath(service.getQualifiedName(), basePackage), renderActionService(service, basePackage));
        }
        var projections = authoredTypes.stream().filter(type -> annotated(type, PROJECTED_STATE)).toList();
        var facts = authoredTypes.stream().filter(type -> annotated(type, FACT)).toList();
        var candidates = authoredTypes.stream().filter(type -> candidateNames.contains(type.getQualifiedName())).toList();
        var events = authoredTypes.stream().filter(type -> annotated(type, EVENT)).toList();
        if (!projections.isEmpty() || !facts.isEmpty() || !actionServices.isEmpty()) {
            files.put(Path.of(basePackage.replace('.', '/'), "GeneratedSemanticBindings.java"),
                    renderBindings(basePackage, projections, facts, candidates, events, actionServices));
        }
        if (!actionServices.isEmpty()) {
            files.put(Path.of(basePackage.replace('.', '/'), "GeneratedSemanticRegistry.java"),
                    renderRegistry(basePackage, candidates));
        }
        return files;
    }

    private static String renderBindings(
            String basePackage,
            List<Type> projections,
            List<Type> facts,
            List<Type> candidates,
            List<Type> events,
            List<Service> actionServices) {
        String projectionTypes = projections.stream()
                .map(type -> basePackage + "." + type.getQualifiedName() + ".TYPE")
                .collect(java.util.stream.Collectors.joining(", "));
        String factTypes = facts.stream()
                .map(type -> basePackage + "." + type.getQualifiedName() + ".TYPE")
                .collect(java.util.stream.Collectors.joining(", "));
        String candidateTypes = candidates.stream()
                .map(type -> basePackage + "." + type.getQualifiedName() + ".TYPE")
                .collect(java.util.stream.Collectors.joining(", "));
        String eventTypes = events.stream()
                .map(type -> basePackage + "." + type.getQualifiedName() + ".TYPE")
                .collect(java.util.stream.Collectors.joining(", "));
        String actionTypes = actionServices.stream().flatMap(service -> service.getOperations().stream()
                        .map(operation -> basePackage + "." + service.getQualifiedName() + "."
                                + constantName(operation.getName())))
                .collect(java.util.stream.Collectors.joining(", "));
        return "package " + basePackage + ";\n\n"
                + "public final class GeneratedSemanticBindings {\n"
                + "    public static final io.github.gmcnicol.kernel.semanticpack.SemanticBindings INSTANCE = new "
                + "io.github.gmcnicol.kernel.semanticpack.SemanticBindings(java.util.List.of(" + projectionTypes
                + "), java.util.List.of(" + factTypes + "), java.util.List.of(" + candidateTypes
                + "), java.util.List.of(" + eventTypes + "), java.util.List.of(" + actionTypes + "));\n\n"
                + "    private GeneratedSemanticBindings() {}\n"
                + "}\n";
    }

    private static String renderRegistry(String basePackage, List<Type> candidates) {
        String decoders = candidates.stream().map(type -> {
            ObjectType candidate = (ObjectType) type;
            String arguments = candidate.getFields().stream()
                    .map(field -> formValue(field, basePackage))
                    .collect(java.util.stream.Collectors.joining(", "));
            String javaType = basePackage + "." + type.getQualifiedName();
            return "io.github.gmcnicol.kernel.application.SemanticRegistry.formDecoder("
                    + javaType + ".TYPE, form -> new " + javaType + "(" + arguments + "))";
        }).collect(java.util.stream.Collectors.joining(", "));
        return "package " + basePackage + ";\n\n"
                + "public final class GeneratedSemanticRegistry {\n"
                + "    public static final io.github.gmcnicol.kernel.application.SemanticRegistry INSTANCE = "
                + "io.github.gmcnicol.kernel.application.SemanticRegistry.generated("
                + "GeneratedSemanticBindings.INSTANCE, java.util.List.of(" + decoders + "));\n\n"
                + "    private GeneratedSemanticRegistry() {}\n"
                + "}\n";
    }

    private static String formValue(Field field, String basePackage) {
        Type type = field.getType();
        String method;
        Type parsedType;
        if (type instanceof ArrayType array) {
            method = field.getNullable() ? "optionalList" : "list";
            parsedType = array.getMemberType();
        } else {
            method = field.getNullable() ? "optional" : "required";
            parsedType = type;
        }
        return "form." + method + "(\"" + field.getName() + "\", " + formParser(parsedType, basePackage) + ")";
    }

    private static String formParser(Type type, String basePackage) {
        if (type instanceof ArrayType || type instanceof ObjectType object && object.getTypeKind() == TypeKind.Model) {
            return "value -> { throw new IllegalArgumentException(\"Use JSON for nested Candidate fields\"); }";
        }
        if (type instanceof EnumType) {
            return "value -> " + basePackage + "." + type.getQualifiedName() + ".valueOf(value)";
        }
        if (type instanceof ObjectType object) {
            return "value -> new " + basePackage + "." + type.getQualifiedName()
                    + "(" + formPrimitive(primitive(object), "value") + ")";
        }
        return "value -> " + formPrimitive((PrimitiveType) type, "value");
    }

    private static String formPrimitive(PrimitiveType primitive, String value) {
        if (primitive == PrimitiveType.BOOLEAN) {
            return "switch (" + value + ") { case \"true\" -> true; case \"false\" -> false; "
                    + "default -> throw new IllegalArgumentException(\"Invalid Boolean\"); }";
        }
        if (primitive == PrimitiveType.DOUBLE) {
            return "java.util.Optional.of(java.lang.Double.valueOf(" + value + "))"
                    + ".filter(java.lang.Double::isFinite).orElseThrow(() -> "
                    + "new IllegalArgumentException(\"Invalid Double\"))";
        }
        return parsePrimitive(primitive, value);
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
        String descriptor = annotated(type, SUBJECT)
                ? "    public static final io.github.gmcnicol.kernel.application.SubjectType<" + name + "> TYPE = "
                        + "new io.github.gmcnicol.kernel.application.SubjectType<>(\"" + type.getQualifiedName()
                        + "\", " + name + ".class, value -> value.value().toString(), "
                        + subjectParser(type, name) + ");\n\n"
                : "";
        return """
                package %s;

                import com.fasterxml.jackson.annotation.JsonCreator;
                import com.fasterxml.jackson.annotation.JsonValue;
                import java.util.Objects;

                public record %s(@JsonValue %s value) {
                %s
                    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
                    public %s {
                        Objects.requireNonNull(value, "value");
                    }
                }
                """.formatted(packageName, name, valueType, descriptor.stripTrailing(), name);
    }

    private static String renderModel(
            ObjectType type, String basePackage, boolean candidate, List<String> unionInterfaces) {
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
        var descriptors = new StringBuilder();
        for (Field field : fields) {
            descriptors.append("    public static final io.github.gmcnicol.kernel.application.FieldType<")
                    .append(name).append(", ").append(javaType(field.getType(), field.getNullable(), basePackage))
                    .append("> ").append(constantName(field.getName())).append(" = new ")
                    .append("io.github.gmcnicol.kernel.application.FieldType<>(\"")
                    .append(type.getQualifiedName()).append(".").append(field.getName()).append("\", ")
                    .append(name).append("::").append(field.getName()).append(", value -> ")
                    .append(cedarOptionalValue(field.getType(), field.getNullable(), "value", basePackage))
                    .append(");\n");
        }
        if (annotated(type, PROJECTED_STATE)) {
            String subjectName = annotationString(type, PROJECTED_STATE, "subject");
            descriptors.append("    public static final io.github.gmcnicol.kernel.application.ProjectionType<")
                    .append(basePackage).append(".").append(subjectName).append(", ").append(name)
                    .append("> TYPE = new ")
                    .append("io.github.gmcnicol.kernel.application.ProjectionType<>(\"")
                    .append(type.getQualifiedName()).append("\", ").append(contractVersion(type)).append(", ")
                    .append(basePackage).append(".").append(subjectName).append(".TYPE, ")
                    .append(name).append(".class, java.util.List.of(")
                    .append(fields.stream().map(field -> constantName(field.getName())).collect(java.util.stream.Collectors.joining(", ")))
                    .append("));\n");
        }
        if (annotated(type, FACT)) {
            String projectionName = annotationString(type, FACT, "projection");
            descriptors.append("    public static final io.github.gmcnicol.kernel.application.FactType<")
                    .append(name).append("> TYPE = new io.github.gmcnicol.kernel.application.FactType<>(\"")
                    .append(type.getQualifiedName()).append("\", ").append(contractVersion(type)).append(", ")
                    .append(basePackage).append(".").append(projectionName).append(".TYPE, ")
                    .append(name).append(".class, java.util.List.of(")
                    .append(fields.stream().map(field -> constantName(field.getName()))
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append("));\n")
                    .append("    public static final io.github.gmcnicol.kernel.semanticpack.FactDerivationSlot<")
                    .append(basePackage).append(".").append(projectionName).append(", ").append(name)
                    .append("> DERIVATION = new io.github.gmcnicol.kernel.semanticpack.FactDerivationSlot<>(TYPE);\n");
        }
        if (annotated(type, EVENT)) {
            descriptors.append("    public static final io.github.gmcnicol.kernel.application.EventType<")
                    .append(name).append("> TYPE = new io.github.gmcnicol.kernel.application.EventType<>(\"")
                    .append(type.getQualifiedName()).append("\", ").append(contractVersion(type)).append(", ")
                    .append(name).append(".class);\n");
        } else if (candidate) {
            descriptors.append("    public static final io.github.gmcnicol.kernel.application.CandidateType<")
                    .append(name).append("> TYPE = new io.github.gmcnicol.kernel.application.CandidateType<>(\"")
                    .append(type.getQualifiedName()).append("\", ").append(contractVersion(type)).append(", ")
                    .append(name).append(".class, java.util.List.of(")
                    .append(fields.stream().map(field -> constantName(field.getName()))
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append("));\n");
        }
        if (!descriptors.isEmpty()) descriptors.append("\n");
        String implemented = unionInterfaces.isEmpty() ? "" : " implements " + unionInterfaces.stream()
                .map(union -> basePackage + "." + union).collect(java.util.stream.Collectors.joining(", "));
        return "package " + packageName + ";\n\npublic record " + name + "(\n" + components + "\n)" + implemented + " {\n"
                + descriptors + "    public " + name + " {\n" + body + "    }\n}\n";
    }

    private static String renderUnion(ObjectType type, String basePackage) {
        String permitted = eventModels(type).stream()
                .map(event -> basePackage + "." + event.getQualifiedName())
                .collect(java.util.stream.Collectors.joining(", "));
        return "package " + javaPackage(type.getQualifiedName(), basePackage) + ";\n\npublic sealed interface "
                + simpleName(type.getQualifiedName()) + " permits " + permitted + " {}\n";
    }

    private static String renderActionService(Service service, String basePackage) {
        String packageName = javaPackage(service.getQualifiedName(), basePackage);
        String name = simpleName(service.getQualifiedName());
        String projectionName = serviceAnnotationString(service, ACTION_SERVICE, "projection");
        var body = new StringBuilder();
        for (Operation operation : service.getOperations()) {
            Type candidate = operation.getParameters().getFirst().getType();
            Type event = ((ArrayType) operation.getReturnType()).getMemberType();
            String eventDescriptors = eventModels(event).stream()
                    .map(type -> basePackage + "." + type.getQualifiedName() + ".TYPE")
                    .collect(java.util.stream.Collectors.joining(", "));
            body.append("    public static final io.github.gmcnicol.kernel.application.ActionType<")
                    .append(basePackage).append(".").append(projectionName).append(", ")
                    .append(basePackage).append(".").append(candidate.getQualifiedName()).append(", ")
                    .append(basePackage).append(".").append(event.getQualifiedName()).append("> ")
                    .append(constantName(operation.getName())).append(" = new ")
                    .append("io.github.gmcnicol.kernel.application.ActionType<>(\"")
                    .append(service.getQualifiedName()).append(".").append(operation.getName()).append("\", ")
                    .append(basePackage).append(".").append(projectionName).append(".TYPE, ")
                    .append(basePackage).append(".").append(candidate.getQualifiedName()).append(".TYPE, ")
                    .append("java.util.List.of(").append(eventDescriptors).append("));\n");
        }
        return "package " + packageName + ";\n\npublic final class " + name + " {\n" + body
                + "\n    private " + name + "() {}\n}\n";
    }

    private static String constantName(String fieldName) {
        return fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    private static String immutableList(ArrayType array, String expression) {
        if (array.getMemberType() instanceof ArrayType member) {
            return expression + ".stream().map(value -> " + immutableList(member, "value") + ").toList()";
        }
        return "java.util.List.copyOf(" + expression + ")";
    }

    private static String parsePrimitive(PrimitiveType primitive, String value) {
        return switch (primitive) {
            case STRING -> value;
            case BOOLEAN -> "java.lang.Boolean.valueOf(" + value + ")";
            case INTEGER -> "java.lang.Integer.valueOf(" + value + ")";
            case LONG -> "java.lang.Long.valueOf(" + value + ")";
            case DECIMAL -> "new java.math.BigDecimal(" + value + ")";
            case DOUBLE -> "java.lang.Double.valueOf(" + value + ")";
            case LOCAL_DATE -> "java.time.LocalDate.parse(" + value + ")";
            case TIME -> "java.time.LocalTime.parse(" + value + ")";
            case DATE_TIME -> "java.time.LocalDateTime.parse(" + value + ")";
            case INSTANT -> "java.time.Instant.parse(" + value + ")";
            default -> throw new IllegalArgumentException("Unsupported Subject primitive: " + primitive);
        };
    }

    private static String subjectParser(ObjectType type, String name) {
        return switch (primitive(type)) {
            case BOOLEAN -> "value -> new " + name + "(switch (value) { case \"true\" -> true; "
                    + "case \"false\" -> false; default -> throw new IllegalArgumentException("
                    + "\"Invalid Boolean Subject ID\"); })";
            case DOUBLE -> "value -> { double parsed = java.lang.Double.parseDouble(value); "
                    + "if (!java.lang.Double.isFinite(parsed)) throw new IllegalArgumentException("
                    + "\"Invalid Double Subject ID\"); return new " + name + "(parsed); }";
            default -> "value -> new " + name + "(" + parsePrimitive(primitive(type), "value") + ")";
        };
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

    private static String cedarOptionalValue(Type type, boolean nullable, String value, String basePackage) {
        return nullable
                ? value + ".map(optionalValue -> " + cedarValue(type, "optionalValue", basePackage) + ")"
                : "java.util.Optional.of(" + cedarValue(type, value, basePackage) + ")";
    }

    private static String cedarValue(Type type, String value, String basePackage) {
        return cedarValue(type, value, basePackage, 0);
    }

    private static String cedarValue(Type type, String value, String basePackage, int depth) {
        if (type instanceof ArrayType array) {
            String item = "item" + depth;
            return "new com.cedarpolicy.value.CedarList(" + value
                    + ".stream().<com.cedarpolicy.value.Value>map(" + item + " -> "
                    + cedarValue(array.getMemberType(), item, basePackage, depth + 1) + ").toList())";
        }
        if (type instanceof PrimitiveType primitive) return cedarPrimitive(primitive, value);
        if (type instanceof EnumType) return "new com.cedarpolicy.value.PrimString(" + value + ".name())";
        if (type instanceof ObjectType object && object.getTypeKind() == TypeKind.Type) {
            return cedarPrimitive(primitive(object), value + ".value()");
        }
        if (type instanceof ObjectType object) {
            String entries = object.getFields().stream().map(field -> {
                String fieldValue = value + "." + field.getName() + "()";
                String optionalValue = "optionalValue" + depth;
                String entry = "java.util.Map.entry(\"" + field.getName()
                        + "\", " + cedarValue(field.getType(),
                                field.getNullable() ? optionalValue : fieldValue, basePackage, depth + 1)
                        + ")";
                return field.getNullable()
                        ? fieldValue + ".map(" + optionalValue + " -> " + entry + ")"
                        : "java.util.Optional.of(" + entry + ")";
            }).collect(java.util.stream.Collectors.joining(", "));
            return "new com.cedarpolicy.value.CedarMap(java.util.stream.Stream"
                    + ".<java.util.Optional<java.util.Map.Entry<String, com.cedarpolicy.value.Value>>>of("
                    + entries + ").flatMap(java.util.Optional::stream).collect(java.util.stream.Collectors"
                    + ".toUnmodifiableMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)))";
        }
        throw new IllegalStateException("Validated type has no Cedar representation: " + type.getQualifiedName());
    }

    private static String cedarPrimitive(PrimitiveType primitive, String value) {
        if (primitive == PrimitiveType.STRING) return "new com.cedarpolicy.value.PrimString(" + value + ")";
        if (primitive == PrimitiveType.BOOLEAN) return "new com.cedarpolicy.value.PrimBool(" + value + ")";
        if (primitive == PrimitiveType.INTEGER || primitive == PrimitiveType.LONG) {
            return "new com.cedarpolicy.value.PrimLong(" + value + ".longValue())";
        }
        if (primitive == PrimitiveType.DECIMAL || primitive == PrimitiveType.DOUBLE) {
            return "new com.cedarpolicy.value.Decimal(" + value + ".toString())";
        }
        return "new com.cedarpolicy.value.PrimString(" + value + ".toString())";
    }

    private static Path javaPath(Type type, String basePackage) {
        return javaPath(type.getQualifiedName(), basePackage);
    }

    private static Path javaPath(String qualifiedName, String basePackage) {
        return Path.of((basePackage + "." + qualifiedName).replace('.', '/') + ".java");
    }

    private static String javaPackage(Type type, String basePackage) {
        return javaPackage(type.getQualifiedName(), basePackage);
    }

    private static String javaPackage(String qualifiedName, String basePackage) {
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

    private static String location(Compiled compiled) {
        if (compiled.getCompilationUnits().isEmpty()) return compiled.toString();
        var unit = compiled.getCompilationUnits().getFirst();
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
