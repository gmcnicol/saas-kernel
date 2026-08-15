package io.github.gmcnicol;

import static org.assertj.core.api.Assertions.assertThat;

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
}
