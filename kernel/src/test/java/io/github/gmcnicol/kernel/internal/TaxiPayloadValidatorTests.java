package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lang.taxi.Compiler;
import lang.taxi.CompilerConfig;
import org.junit.jupiter.api.Test;

class TaxiPayloadValidatorTests {

    @Test
    void rejectsUnsupportedActionInputShapesAtStartup() {
        var taxi = new Compiler("""
                namespace example
                model Nested { value: String }
                model Input { nested: Nested }
                model Output { value: String }
                service Actions { operation act(input: Input): Output }
                """, "unsupported.taxi", List.of(), new CompilerConfig()).compile();

        assertThatThrownBy(() -> new TaxiPayloadValidator(taxi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single flat models");
    }
}
