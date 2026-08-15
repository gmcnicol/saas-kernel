package io.github.gmcnicol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.internal.AssemblyAutoConfiguration;
import io.github.gmcnicol.kernel.semanticpack.AuthorisationBundle;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApplicationAssemblyTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AssemblyAutoConfiguration.class));

    @Test
    void rejectsMissingSemanticPackAndAuthorisationBundle() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Application must register exactly one Semantic Pack, found 0");
        });
    }

    @Test
    void rejectsMissingManifestResources() {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> "missing-semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "missing-authorisation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Assembly resource not found: missing-semantic.properties");
                });
    }

    @Test
    void rejectsMalformedSemanticManifest() {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> "assembly/invalid-semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/valid-authorisation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Semantic Pack manifest missing: id");
                });
    }

    @Test
    void rejectsDuplicateSemanticPacks() {
        contextRunner
                .withBean("firstSemanticPack", SemanticPack.class, () -> () -> "assembly/semantic.properties")
                .withBean("secondSemanticPack", SemanticPack.class, () -> () -> "assembly/semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/authorisation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Application must register exactly one Semantic Pack, found 2");
                });
    }

    @Test
    void rejectsIncompatibleManifestFormat() {
        runInvalid("assembly/incompatible-semantic.properties", "assembly/authorisation.properties",
                "Semantic Pack manifest has unsupported format-version: 2");
    }

    @Test
    void rejectsChecksumMismatch() {
        runInvalid("assembly/tampered-semantic.properties", "assembly/authorisation.properties",
                "Assembly checksum mismatch: assembly/tampered-semantic.properties");
    }

    @Test
    void rejectsInvalidTaxi() {
        runInvalid("assembly/invalid-taxi.properties", "assembly/authorisation.properties", "Taxi compilation failed:");
    }

    @Test
    void rejectsInvalidCedar() {
        runInvalid("assembly/semantic.properties", "assembly/invalid-authorisation.properties", "Cedar validation failed:");
    }

    @Test
    void rejectsMissingJavaBindings() {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> "assembly/semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/authorisation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Semantic Pack implementation does not match manifest: DERIVATION");
                });
    }

    @Test
    void acceptsValidAssembly() {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> "assembly/semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/authorisation.properties")
                .withBean("derivation", SemanticImplementation.class,
                        () -> new SemanticImplementation(SemanticImplementation.Kind.DERIVATION, "test.SampleFact"))
                .withBean("applicability", SemanticImplementation.class,
                        () -> new SemanticImplementation(SemanticImplementation.Kind.APPLICABILITY, "test.Actions.act"))
                .withBean("handler", SemanticImplementation.class,
                        () -> new SemanticImplementation(SemanticImplementation.Kind.HANDLER, "test.Actions.act"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    private void runInvalid(String semantic, String authorisation, String messagePrefix) {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> semantic)
                .withBean(AuthorisationBundle.class, () -> () -> authorisation)
                .withBean("derivation", SemanticImplementation.class,
                        () -> new SemanticImplementation(SemanticImplementation.Kind.DERIVATION, "test.SampleFact"))
                .withBean("applicability", SemanticImplementation.class,
                        () -> new SemanticImplementation(SemanticImplementation.Kind.APPLICABILITY, "test.Actions.act"))
                .withBean("handler", SemanticImplementation.class,
                        () -> new SemanticImplementation(SemanticImplementation.Kind.HANDLER, "test.Actions.act"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(messagePrefix);
                });
    }
}
