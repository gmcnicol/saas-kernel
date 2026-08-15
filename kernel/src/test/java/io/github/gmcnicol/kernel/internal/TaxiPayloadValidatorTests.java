package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
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

        assertThatThrownBy(() -> new TaxiPayloadValidator(taxi, Set.of("example.Actions.act")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single flat models");
    }

    @Test
    void ignoresUnsupportedOperationsNotBoundAsActions() {
        var taxi = new Compiler("""
                namespace example
                model Nested { value: String }
                model Flat { value: String }
                model Output { value: String }
                service Actions {
                    operation act(input: Flat): Output
                    operation support(input: Nested): Output
                }
                """, "supporting.taxi", List.of(), new CompilerConfig()).compile();

        assertThatCode(() -> new TaxiPayloadValidator(taxi, Set.of("example.Actions.act")))
                .doesNotThrowAnyException();
    }
}
