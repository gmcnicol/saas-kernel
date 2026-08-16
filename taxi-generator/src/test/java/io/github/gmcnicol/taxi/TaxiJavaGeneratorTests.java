package io.github.gmcnicol.taxi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaxiJavaGeneratorTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cleanGenerationIsByteIdenticalAndRemovesStaleTypes() throws IOException {
        Path source = temporaryDirectory.resolve("src/schema.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                namespace example
                type Identifier inherits String
                model Present { id: Identifier }
                model Removed { id: Identifier }
                """);

        TaxiJavaGenerator.generate(source.getParent(), output, "generated");
        Map<Path, byte[]> first = files(output);
        TaxiJavaGenerator.generate(source.getParent(), output, "generated");
        Map<Path, byte[]> second = files(output);

        assertTrue(first.keySet().equals(second.keySet()));
        first.forEach((path, bytes) -> assertArrayEquals(bytes, second.get(path)));

        Files.writeString(source, """
                namespace example
                type Identifier inherits String
                model Present { id: Identifier }
                """);
        TaxiJavaGenerator.generate(source.getParent(), output, "generated");

        assertFalse(Files.exists(output.resolve("generated/example/Removed.java")));
    }

    @Test
    void reportsCompilerAndGeneratorFailuresWithTaxiLocation() throws IOException {
        Path source = temporaryDirectory.resolve("src/broken.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace example\nmodel Broken { missing String }\n");

        var compilerFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(compilerFailure.getMessage().matches("(?s).*broken\\.taxi:[0-9]+:[0-9]+:.*"));

        Files.writeString(source, "namespace example\nmodel Parent {}\nmodel Child inherits Parent {}\n");
        var unsupported = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(
                unsupported.getMessage().matches("(?s).*broken\\.taxi:[0-9]+:[0-9]+: model inheritance is unsupported.*"),
                unsupported::getMessage);
    }

    @Test
    void rejectsJavaKeywordsInsteadOfManglingThem() throws IOException {
        Path source = temporaryDirectory.resolve("src/keyword.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace example\nmodel Valid { record: String }\n");

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));

        assertTrue(failure.getMessage().matches("(?s).*keyword\\.taxi:[0-9]+:[0-9]+: invalid Java identifier 'record'.*"));

        var invalidPackage = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated."));
        assertTrue(invalidPackage.getMessage().contains("base package: invalid Java identifier ''"));
    }

    @Test
    void rejectsJavaPackageTypeCollisions() throws IOException {
        Path source = temporaryDirectory.resolve("src/collision.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace a\nmodel Foo {}\n");
        Files.writeString(source.resolveSibling("nested.taxi"), "namespace a.Foo\nmodel Bar {}\n");

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));

        assertTrue(
                failure.getMessage().matches("(?s).*\\.taxi:[0-9]+:[0-9]+: generated-name collision with a.Foo.*"),
                failure::getMessage);
    }

    @Test
    void rejectsObjectValuedEnums() throws IOException {
        Path source = temporaryDirectory.resolve("src/object-enum.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                namespace example
                model Details { code: Int }
                enum Errors<Details> { Bad({ code: 1 }) }
                """);

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));

        assertTrue(failure.getMessage().matches("(?s).*object-enum\\.taxi:[0-9]+:[0-9]+: only simple symbolic enums are supported.*"));
    }

    @Test
    void rejectsRecursionAndForbiddenRecordComponents() throws IOException {
        Path source = temporaryDirectory.resolve("src/invalid-model.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace example\nmodel Node { children: Node[] }\n");

        var recursion = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(recursion.getMessage().contains("recursive models are unsupported"), recursion::getMessage);

        Files.writeString(source, "namespace example\nmodel Invalid { wait: String }\n");
        var component = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(component.getMessage().matches("(?s).*invalid-model\\.taxi:[0-9]+:[0-9]+: invalid Java record component 'wait'.*"));
    }

    @Test
    void rejectsAuthoredComputedFunctions() throws IOException {
        Path source = temporaryDirectory.resolve("src/function.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "namespace example\nfunction echo(value: String): String -> value\n");

        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));

        assertTrue(failure.getMessage().matches("(?s).*function\\.taxi:[0-9]+:[0-9]+: computed expressions are unsupported.*"));
    }

    private static Map<Path, byte[]> files(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).collect(Collectors.toMap(root::relativize, path -> read(path)));
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
