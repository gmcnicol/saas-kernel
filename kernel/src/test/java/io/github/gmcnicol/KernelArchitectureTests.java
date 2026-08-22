package io.github.gmcnicol;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.NamedInterface;

class KernelArchitectureTests {

    private final ApplicationModules modules = ApplicationModules.of(KernelArchitecture.class);

    @Test
    void kernelIsOneModuleWithThreeNamedInterfaces() {
        modules.verify();

        assertThat(modules.stream()).singleElement()
                .satisfies(module -> assertThat(module.getNamedInterfaces().stream()
                        .filter(NamedInterface::isNamed)
                        .map(NamedInterface::getName))
                        .containsExactlyInAnyOrder("semantic-pack", "presentation-pack", "application"));
    }

    @Test
    void workflowHotPathsDoNotParseTaxiOrDiscoverModelsReflectively() {
        var classes = new ClassFileImporter().importPackages("io.github.gmcnicol.kernel.internal");

        noClasses().that().haveSimpleName("DefaultKernel")
                .or().haveSimpleName("TypedActionService")
                .or().haveSimpleName("CedarAuthoriser")
                .should().dependOnClassesThat().resideInAnyPackage("lang.taxi..", "java.lang.reflect..")
                .check(classes);
    }

    @Test
    void workflowCodecCallsStayWithinReviewedStructuralBudget() throws IOException {
        String evaluation = Files.readString(Path.of(
                "src/main/java/io/github/gmcnicol/kernel/internal/DefaultKernel.java"));
        String actions = Files.readString(Path.of(
                "src/main/java/io/github/gmcnicol/kernel/internal/TypedActionService.java"));
        String codec = Files.readString(Path.of(
                "src/main/java/io/github/gmcnicol/kernel/internal/SemanticCodec.java"));

        assertThat(occurrences(evaluation, "canonical.encode(")).isEqualTo(2);
        assertThat(occurrences(evaluation, "canonical.decode(")).isEqualTo(1);
        assertThat(occurrences(actions, "canonical.encode(")).isEqualTo(3);
        assertThat(occurrences(actions, "canonical.decode(")).isEqualTo(4);
        assertThat(occurrences(actions, "canonical.decodeEvent(")).isEqualTo(1);
        assertThat(occurrences(codec, "new CanonicalCodec(")).isEqualTo(1);
    }

    @Test
    void applicationProjectionAndPresentationHotPathsDoNotParseTaxiOrUseReflection() throws IOException {
        Path applications = Path.of("../applications");
        try (var paths = Files.walk(applications)) {
            List<Path> hotPaths = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.contains("Presentation") || name.contains("SemanticConfiguration")
                                || name.equals("CrmA2uiAdapter.java");
                    }).toList();
            assertThat(hotPaths).isNotEmpty();
            for (Path path : hotPaths) {
                assertThat(Files.readString(path))
                        .as(path.toString())
                        .doesNotContain("lang.taxi", "java.lang.reflect");
            }
        }
    }

    private static int occurrences(String source, String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }
}
