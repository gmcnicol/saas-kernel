package io.github.gmcnicol.taxi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaxiJavaGeneratorTests {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsChecksummedPublicationBundleWithApplicationLocalImportedBindings() throws IOException {
        Path source = temporaryDirectory.resolve("semantic-pack/schema.taxi");
        Path output = temporaryDirectory.resolve("generated");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Published
                import shared.CustomerEmail
                namespace application
                @Published model Contact { primaryEmail: CustomerEmail }
                @Published service Directory {
                    operation find(contactEmail: CustomerEmail): Contact
                }
                """);
        String shared = """
                import io.github.gmcnicol.kernel.taxi.Published
                namespace shared
                @Published type CustomerEmail inherits String
                """;
        var imported = new TaxiJavaGenerator.ImportedSource(
                "example:vocabulary:1.0.0", "shared.taxi", shared, sha256(shared));

        TaxiJavaGenerator.generate(source.getParent(), output, "generated", "1.0.0", List.of(imported));

        assertTrue(Files.exists(output.resolve("generated/shared/CustomerEmail.java")));
        assertTrue(Files.readString(output.resolve("generated/application/Contact.java"))
                .contains("generated.shared.CustomerEmail primaryEmail"));
        assertFalse(Files.exists(output.resolve("generated/application/Directory.java")));
        String manifest = Files.readString(
                output.resolve("META-INF/saas-kernel/publication/manifest.properties"));
        assertTrue(manifest.contains("dependency.0=example:vocabulary:1.0.0|shared.taxi|" + sha256(shared)), manifest);
        assertTrue(manifest.contains("type.0=application.Contact|"), manifest);
        assertTrue(manifest.contains("type.1=shared.CustomerEmail|"), manifest);
        assertTrue(manifest.contains("service.0=application.Directory|SERVICE"), manifest);
        String published = publicationSources(output);
        assertTrue(published.contains("primaryEmail: CustomerEmail"), published);
        assertTrue(published.contains("contactEmail: CustomerEmail"), published);
        assertTrue(published.contains("namespace shared"), published);
    }

    @Test
    void rejectsTransitivePublicationOfUnpublishedType() throws IOException {
        Path source = temporaryDirectory.resolve("semantic-pack/schema.taxi");
        Path output = temporaryDirectory.resolve("generated");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Published
                namespace application
                model Secret { value: String }
                @Published model PublicContact { secret: Secret }
                """);

        var failure = assertThrows(IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));

        assertTrue(failure.getMessage().contains(
                "published root application.PublicContact leaks unpublished type application.Secret"),
                failure::getMessage);
    }

    @Test
    void publishesActionServicesAsDescriptionsOnly() throws IOException {
        Path source = temporaryDirectory.resolve("action/schema.taxi");
        Path output = temporaryDirectory.resolve("action/generated");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                import io.github.gmcnicol.kernel.taxi.Published
                namespace published
                @Subject @Published type Id inherits String
                @Contract(version = 1) @ProjectedState(subject = "published.Id") @Published
                model State { id: Id }
                @Contract(version = 1) @Published model Input { note: String }
                @Contract(version = 1) @Event @Published closed model Changed { id: Id }
                @ActionService(projection = "published.State") @Published service Actions {
                    operation change(input: Input): Changed[]
                }
                """);

        TaxiJavaGenerator.generate(source.getParent(), output, "generated");

        String manifest = Files.readString(
                output.resolve("META-INF/saas-kernel/publication/manifest.properties"));
        assertTrue(manifest.contains("service.0=published.Actions|ACTION|"), manifest);
        assertFalse(manifest.matches("(?s).*(handler|endpoint|authorit).*"), manifest);
        assertTrue(Files.readString(output.resolve("generated/published/Actions.java"))
                .contains("ActionType<"));
    }

    @Test
    void definitionChecksumIgnoresUnrelatedDefinitionsInSameSource() throws IOException {
        Path source = temporaryDirectory.resolve("checksums/schema.taxi");
        Path output = temporaryDirectory.resolve("checksum-output");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Published
                namespace published
                @Published model Stable { value: String }
                @Published model Changing { value: String }
                """);
        TaxiJavaGenerator.generate(source.getParent(), output, "generated");
        String first = publicationLine(output, "type.1=published.Stable|");

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Published
                namespace published
                @Published model Stable { value: String }
                @Published model Changing { value: String, count: Int }
                """);
        TaxiJavaGenerator.generate(source.getParent(), output, "generated");

        assertTrue(first.equals(publicationLine(output, "type.1=published.Stable|")));
    }

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

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Event
                namespace a
                @Subject type Id inherits String
                @Contract(version = 1) @ProjectedState(subject = "a.Id") model State {}
                @Contract(version = 1) model Payload {}
                @Contract(version = 1) @Event closed model Changed {}
                model Foo {}
                """);
        Files.writeString(source.resolveSibling("nested.taxi"), """
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace a.Foo
                @ActionService(projection = "a.State") service Actions {
                    operation act(input: a.Payload): a.Changed[]
                }
                """);
        var actionCollision = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(actionCollision.getMessage().contains("generated-name collision"), actionCollision::getMessage);
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

    @Test
    void generatesRoleCheckedTypedDescriptors() throws IOException {
        Path source = temporaryDirectory.resolve("src/typed.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Fact
                namespace example
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId")
                model Customer { id: CustomerId, balance: Decimal }
                @Contract(version = 2) @Fact(projection = "example.Customer")
                model Overdrawn { amount: Decimal }
                """);

        TaxiJavaGenerator.generate(source.getParent(), output, "generated");

        String subject = Files.readString(output.resolve("generated/example/CustomerId.java"));
        String projection = Files.readString(output.resolve("generated/example/Customer.java"));
        String fact = Files.readString(output.resolve("generated/example/Overdrawn.java"));
        String bindings = Files.readString(output.resolve("generated/GeneratedSemanticBindings.java"));
        assertTrue(subject.contains("SubjectType<CustomerId> TYPE"), subject);
        assertTrue(projection.contains("FieldType<Customer, generated.example.CustomerId> ID"), projection);
        assertTrue(projection.contains("ProjectionType<generated.example.CustomerId, Customer> TYPE"), projection);
        assertTrue(projection.contains("CustomerId.TYPE"), projection);
        assertTrue(fact.contains("FactType<Overdrawn> TYPE"), fact);
        assertTrue(fact.contains("FactDerivationSlot<generated.example.Customer, Overdrawn> DERIVATION"), fact);
        assertTrue(fact.contains("Customer.TYPE"), fact);
        assertTrue(bindings.contains("generated.example.Customer.TYPE"), bindings);
        assertTrue(bindings.contains("generated.example.Overdrawn.TYPE"), bindings);

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                namespace example
                type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId")
                model Customer { id: CustomerId }
                """);
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(failure.getMessage().contains("@ProjectedState subject must reference one @Subject scalar"),
                failure::getMessage);

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                namespace example
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId") model First {}
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId") model Second {}
                """);
        var duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(duplicate.getMessage().contains("only one @ProjectedState contract version is allowed for @Subject"),
                duplicate::getMessage);

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Fact
                namespace example
                @Contract(version = 1)
                @ProjectedState(subject = "example.CustomerId")
                @Fact(projection = "example.Customer")
                model Both {}
                """);
        var combined = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(combined.getMessage().contains("semantic role annotations cannot be combined"),
                combined::getMessage);

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                namespace GeneratedSemanticBindings
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "GeneratedSemanticBindings.CustomerId")
                model Customer {}
                """);
        var inventoryCollision = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(inventoryCollision.getMessage().contains(
                "generated-name collision with GeneratedSemanticBindings"), inventoryCollision::getMessage);

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace GeneratedSemanticRegistry
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "GeneratedSemanticRegistry.CustomerId")
                model Customer {}
                @Contract(version = 1) model Payload {}
                @Contract(version = 1) @Event closed model Changed {}
                @ActionService(projection = "GeneratedSemanticRegistry.Customer") service Actions {
                    operation change(input: Payload): Changed[]
                }
                """);
        var registryCollision = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(registryCollision.getMessage().contains(
                "generated-name collision with GeneratedSemanticRegistry"), registryCollision::getMessage);
    }

    @Test
    void generatesOnlyMarkedActionsAndClosedEventUnions() throws IOException {
        Path source = temporaryDirectory.resolve("src/actions.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace example
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId")
                model Customer { id: CustomerId }
                @Contract(version = 2) model ChangeCustomer {}
                @Contract(version = 3) @Event closed model CustomerChanged { id: CustomerId }
                @Contract(version = 4) @Event closed model CustomerSuspended { id: CustomerId }
                closed model CustomerEvent = CustomerChanged | CustomerSuspended
                service Descriptions { operation describe(input: ChangeCustomer): CustomerChanged[] }
                @ActionService(projection = "example.Customer")
                service CustomerActions {
                    operation change(input: ChangeCustomer): CustomerChanged[]
                    operation changeOrSuspend(input: ChangeCustomer): CustomerEvent[]
                }
                """);

        TaxiJavaGenerator.generate(source.getParent(), output, "generated");

        assertFalse(Files.exists(output.resolve("generated/example/Descriptions.java")));
        String actions = Files.readString(output.resolve("generated/example/CustomerActions.java"));
        String union = Files.readString(output.resolve("generated/example/CustomerEvent.java"));
        String changed = Files.readString(output.resolve("generated/example/CustomerChanged.java"));
        String registry = Files.readString(output.resolve("generated/GeneratedSemanticRegistry.java"));
        assertTrue(actions.contains("example.CustomerActions.change"), actions);
        assertTrue(actions.contains("ChangeCustomer.TYPE"), actions);
        assertTrue(actions.contains("CustomerChanged.TYPE"), actions);
        assertTrue(actions.contains("CustomerSuspended.TYPE"), actions);
        assertTrue(union.contains("sealed interface CustomerEvent permits"), union);
        assertTrue(changed.contains("implements generated.example.CustomerEvent"), changed);
        assertTrue(registry.contains("SemanticRegistry.generated("), registry);
        assertTrue(registry.contains("SemanticRegistry.formDecoder("), registry);
    }

    @Test
    void generatesPackagedSemanticIndexFromTaxi() throws IOException {
        Path source = temporaryDirectory.resolve("semantic-pack/schema.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Fact
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace example
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId")
                model Customer { id: CustomerId }
                @Contract(version = 1) @Fact(projection = "example.Customer")
                model CustomerKnown { id: CustomerId }
                @Contract(version = 1) model ChangeCustomer { name: String }
                @Contract(version = 1) @Event closed model CustomerChanged { id: CustomerId }
                @ActionService(projection = "example.Customer") service CustomerActions {
                    operation change(input: ChangeCustomer): CustomerChanged[]
                }
                """);

        TaxiJavaGenerator.generate(source.getParent(), output, "generated", "9.8.7");

        String index = Files.readString(
                output.resolve("META-INF/saas-kernel/semantic-index.properties"));
        assertTrue(index.contains("kernel-version=9.8.7\n"), index);
        assertTrue(index.contains("taxi-compiler-version=1.70.0\n"), index);
        assertTrue(index.contains("source.0=semantic-pack/schema.taxi|"), index);
        assertTrue(index.contains("type.0="), index);
        assertTrue(index.contains("example.Customer|PROJECTION|1|generated.example.Customer|"
                + "example.CustomerId"), index);
        assertTrue(index.contains("action.0=example.CustomerActions.change|example.Customer|"
                + "example.ChangeCustomer|example.CustomerChanged"), index);
        assertTrue(index.contains("slot.0="), index);
        assertTrue(index.contains("DERIVATION|example.CustomerKnown"), index);
        assertTrue(index.contains("PROJECTOR|example.CustomerChanged"), index);
        assertTrue(index.contains("generated-content.0=generated/GeneratedSemanticBindings.class\n"), index);
        assertTrue(index.contains("generated-content.1=generated/GeneratedSemanticRegistry.class\n"), index);
        assertTrue(index.contains("generated-content.2=generated/example/ChangeCustomer.class\n"), index);
        assertTrue(index.contains("generated-content.3=generated/example/Customer.class\n"), index);
        assertTrue(index.contains("generated-content.4=generated/example/CustomerActions.class\n"), index);
        assertTrue(index.contains("generated-content.5=generated/example/CustomerChanged.class\n"), index);
        assertTrue(index.contains("generated-content.6=generated/example/CustomerId.class\n"), index);
        assertTrue(index.contains("generated-content.7=generated/example/CustomerKnown.class\n"), index);
        assertTrue(index.matches("(?s).*index-checksum=[0-9a-f]{64}\\n"), index);
    }

    @Test
    void generatesCandidateDescriptorOnlyForExactActionInput() throws IOException {
        Path source = temporaryDirectory.resolve("src/versions.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Fact
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace example
                @Subject type Id inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.Id")
                model State { id: Id, name: String }
                @Contract(version = 1) @Fact(projection = "example.State")
                model Known { id: Id }
                @Contract(version = 1)
                model Candidate { name: String }
                @Contract(version = 2)
                model CurrentCandidate { name: String, note: String? }
                @Contract(version = 1) @Event closed model Changed { id: Id }
                @Contract(version = 1) model Description { text: String }
                @ActionService(projection = "example.State")
                service HistoricalActions {
                    operation change(input: Candidate): Changed[]
                }
                """);

        TaxiJavaGenerator.generate(source.getParent(), output, "generated");

        String bindings = Files.readString(output.resolve("generated/GeneratedSemanticBindings.java"));
        String state = Files.readString(output.resolve("generated/example/State.java"));
        String candidate = Files.readString(output.resolve("generated/example/Candidate.java"));
        String currentCandidate = Files.readString(output.resolve("generated/example/CurrentCandidate.java"));
        String description = Files.readString(output.resolve("generated/example/Description.java"));
        assertTrue(candidate.contains("CandidateType<Candidate> TYPE"), candidate);
        assertFalse(currentCandidate.contains("CandidateType<CurrentCandidate> TYPE"), currentCandidate);
        assertTrue(state.contains("ProjectionType<"), state);
        assertFalse(description.contains("CandidateType<Description>"), description);
        assertFalse(description.contains("LEGACY_DECODER"), description);
        assertFalse(bindings.contains("LEGACY_DECODER"), bindings);
    }

    @Test
    void rejectsInvalidActionSignatures() throws IOException {
        Path source = temporaryDirectory.resolve("src/invalid-action.taxi");
        Path output = temporaryDirectory.resolve("target/generated-sources/taxi");
        Files.createDirectories(source.getParent());
        String prelude = """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace example
                @Subject type CustomerId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.CustomerId") model Customer {}
                @Contract(version = 1) @Event closed model Changed {}
                """;

        Files.writeString(source, prelude + "@ActionService(projection = \"example.Customer\") service Actions {\n"
                + "operation invalid(input: String): Changed[]\n}\n");
        var primitive = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(primitive.getMessage().contains("exactly one named Candidate Payload model"), primitive::getMessage);

        Files.writeString(source, prelude + "@ActionService(projection = \"example.Customer\") service Actions {\n"
                + "operation invalid(first: Customer, second: Customer): Changed[]\n}\n");
        var multiple = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(multiple.getMessage().contains("exactly one named Candidate Payload model"), multiple::getMessage);

        Files.writeString(source, prelude + "@Contract(version = 1) model Payload {}\n"
                + "@ActionService(projection = \"example.Customer\") service Actions {\n"
                + "operation invalid(input: Payload): Changed\n}\n");
        var returnType = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(returnType.getMessage().contains("non-empty Event array"), returnType::getMessage);

        Files.writeString(source, """
                import io.github.gmcnicol.kernel.taxi.Subject
                import io.github.gmcnicol.kernel.taxi.Contract
                import io.github.gmcnicol.kernel.taxi.ProjectedState
                import io.github.gmcnicol.kernel.taxi.Event
                import io.github.gmcnicol.kernel.taxi.ActionService
                namespace example
                @Subject type FirstId inherits String
                @Subject type SecondId inherits String
                @Contract(version = 1) @ProjectedState(subject = "example.FirstId") model First {}
                @Contract(version = 1) @ProjectedState(subject = "example.SecondId") model Second {}
                @Contract(version = 1) model Payload {}
                @Contract(version = 1) @Event closed model Changed {}
                @ActionService(projection = "example.First") service FirstActions {
                    operation change(input: Payload): Changed[]
                }
                @ActionService(projection = "example.Second") service SecondActions {
                    operation change(input: Payload): Changed[]
                }
                """);
        var sharedEvent = assertThrows(
                IllegalArgumentException.class,
                () -> TaxiJavaGenerator.generate(source.getParent(), output, "generated"));
        assertTrue(sharedEvent.getMessage().contains(
                "Event example.Changed is already owned by Projection example.First"), sharedEvent::getMessage);
    }

    private static Map<Path, byte[]> files(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).collect(Collectors.toMap(root::relativize, path -> read(path)));
        }
    }

    private static String publicationSources(Path output) throws IOException {
        Path root = output.resolve("META-INF/saas-kernel/publication/sources");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).sorted().map(path -> {
                try {
                    return Files.readString(path);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }).collect(Collectors.joining("\n"));
        }
    }

    private static String publicationLine(Path output, String prefix) throws IOException {
        return Files.readAllLines(output.resolve("META-INF/saas-kernel/publication/manifest.properties")).stream()
                .filter(line -> line.startsWith(prefix)).findFirst().orElseThrow();
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
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
