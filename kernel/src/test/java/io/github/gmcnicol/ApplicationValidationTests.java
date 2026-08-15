package io.github.gmcnicol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gmcnicol.kernel.application.AuthorisationBundle;
import io.github.gmcnicol.kernel.application.AuthorisationModel;
import io.github.gmcnicol.kernel.internal.ApplicationValidationAutoConfiguration;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import io.github.gmcnicol.kernel.semanticpack.SemanticImplementation;
import io.github.gmcnicol.kernel.semanticpack.SemanticPack;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApplicationValidationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApplicationValidationAutoConfiguration.class));

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
                            .hasRootCauseMessage("Application resource not found: missing-semantic.properties");
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
    void rejectsMalformedTaxiCoordinate() {
        runInvalid("assembly/invalid-coordinate.properties", "assembly/authorisation.properties",
                "Semantic Pack manifest has invalid Taxi coordinate: ::");
    }

    @Test
    void rejectsChecksumMismatch() {
        runInvalid("assembly/tampered-semantic.properties", "assembly/authorisation.properties",
                "Application checksum mismatch: assembly/tampered-semantic.properties");
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
    void acceptsValidApplication() {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> "assembly/semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/authorisation.properties")
                .withBean("derivation", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.DERIVATION, "test.SampleFact"))
                .withBean("applicability", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.APPLICABILITY, "test.Actions.act"))
                .withBean("handler", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.HANDLER, "test.Actions.act"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void acceptsOnlyDeclaredBindings() {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> "assembly/sparse-semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/authorisation.properties")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void acceptsCompatiblePresentationPack() {
        validApplication("assembly/presentation.properties")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsPresentationFieldOutsideAuthorisationModel() {
        validApplication("assembly/leaked-presentation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Presentation Pack references unauthorised field: test.Entity.secret");
                });
    }

    @Test
    void rejectsPresentationPackWithoutPromisedOfferInput() {
        validApplication("assembly/omitted-offer-presentation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Presentation Pack omits promised Action Offer input: test.SampleInput");
                });
    }

    @Test
    void rejectsIncompatiblePresentationEnvelopeVersion() {
        validApplication("assembly/incompatible-presentation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Presentation Pack manifest has unsupported envelope-version: 2");
                });
    }

    @Test
    void rejectsUnknownPresentationSemanticReference() {
        validApplication("assembly/unknown-presentation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Presentation Pack references unknown Fact: test.MissingFact");
                });
    }

    @Test
    void rejectsDuplicatePresentationActionOffers() {
        validApplication("assembly/duplicate-offer-presentation.properties")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Duplicate Presentation Pack Action Offer: test.Actions.act");
                });
    }

    @Test
    void rejectsRendererThatOmitsPromisedActionOffer() {
        validApplication("assembly/presentation.properties", false)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "Presentation Pack renderer omits or forges promised Action Offers");
                });
    }

    private ApplicationContextRunner validApplication(String presentationManifest) {
        return validApplication(presentationManifest, true);
    }

    private ApplicationContextRunner validApplication(String presentationManifest, boolean renderOffers) {
        return contextRunner
                .withBean(SemanticPack.class, () -> () -> "assembly/semantic.properties")
                .withBean(AuthorisationBundle.class, () -> () -> "assembly/authorisation.properties")
                .withBean(AuthorisationModel.class, () -> new AuthorisationModel() {
                    @Override public String subjectType() { return "test.Entity"; }
                    @Override public String resourceType() { return "Entity"; }
                    @Override public java.util.Map<String, String> fields() {
                        return java.util.Map.of("test.Entity.name", "name");
                    }
                })
                .withBean(PresentationPack.class, () -> PresentationPack.of(
                        presentationManifest, envelope -> new PresentationResult(
                                "", "", renderOffers
                                        ? envelope.actionOffers().stream()
                                                .map(offer -> offer.id())
                                                .collect(java.util.stream.Collectors.toSet())
                                        : java.util.Set.of())))
                .withBean("derivation", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.DERIVATION, "test.SampleFact"))
                .withBean("applicability", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(
                                SemanticImplementation.Kind.APPLICABILITY, "test.Actions.act"))
                .withBean("handler", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.HANDLER, "test.Actions.act"));
    }

    private void runInvalid(String semantic, String authorisation, String messagePrefix) {
        contextRunner
                .withBean(SemanticPack.class, () -> () -> semantic)
                .withBean(AuthorisationBundle.class, () -> () -> authorisation)
                .withBean("derivation", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.DERIVATION, "test.SampleFact"))
                .withBean("applicability", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.APPLICABILITY, "test.Actions.act"))
                .withBean("handler", SemanticImplementation.class,
                        () -> SemanticImplementation.binding(SemanticImplementation.Kind.HANDLER, "test.Actions.act"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining(messagePrefix);
                });
    }
}
