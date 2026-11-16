package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class CedarReadBoundaryTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final SubjectType<String> ROW = new SubjectType<>("boundary.Row", String.class, s -> s);
    private static final SubjectType<String> SCOPE = new SubjectType<>("boundary.Scope", String.class, s -> s);
    private static final FieldType<Row, Value> DATA = new FieldType<>("boundary.View.data", Row::data, Optional::of);
    private static final FieldType<Row, Long> INCREMENT = new FieldType<>("boundary.View.increment", Row::increment,
            value -> Optional.of(new PrimLong(value)));
    private static final CapabilityType<Value> CAN_READ = new CapabilityType<>("boundary.CanRead", 1, Value.class,
            List.of(new FieldType<Value, Value>("boundary.CanRead.data", value -> value, Optional::of)));

    @BeforeAll static void role() {
        new JdbcTemplate(source()).execute("CREATE ROLE kernel_runtime NOLOGIN NOBYPASSRLS");
    }

    @ParameterizedTest @CsvSource({
            "sdk,false,User", "sdk,true,User", "prepared,false,User", "prepared,true,User",
            "sdk,false,decimal", "sdk,true,decimal", "prepared,false,decimal", "prepared,true,decimal"})
    void converterCapabilityAndPolicyErrorsCannotReleaseProtectedReadsOrDisclosures(
            String backend, boolean inCapability, String valueType) throws Exception {
        var accessed = new AtomicInteger();
        var display = new ReadPath<Row, String>("boundary.View.display", "display", row -> {
            accessed.incrementAndGet();
            return ReadValue.shown("protected __entity __extn");
        }, value -> value);
        var projection = new ProjectionType<>("boundary.View", 1, ROW, Row.class, List.of(DATA, INCREMENT), List.of(display));
        var bindings = new SemanticBindings(List.of(projection), List.of(), List.of(), List.of(), List.of(),
                List.of(CAN_READ), List.of(ROW, SCOPE));
        var model = new TypedAuthorisationModel<>(projection, Set.of(DATA, INCREMENT), Set.of(), Set.of(CAN_READ));
        var schema = new Schema("entity User; entity Row { data: " + valueType + ", increment: Long }; "
                + List.of("read", "display").stream().map(operation ->
                        "action \"typed/v1/boundary.View." + operation + "\" appliesTo { principal: User, resource: Row, "
                        + "context: { readScope: String, capabilities: { CanRead: { data: " + valueType + " } } } };")
                        .collect(java.util.stream.Collectors.joining()));
        String expected = valueType.equals("User") ? "principal" : "decimal(\"1.0\")";
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { resource.data == " + expected
                + " && context.capabilities.CanRead.data == " + expected + " }; "
                + "forbid(principal, action, resource) when { 9223372036854775807 + resource.increment == 0 };");
        assertThat(new BasicAuthorizationEngine().validate(new ValidationRequest(schema, policies)).validationPassed()).isTrue();
        Value typed = valueType.equals("User")
                ? new EntityUID(EntityTypeName.parse("User").orElseThrow(), "u") : new Decimal("1.0");
        var capabilityValue = new java.util.concurrent.atomic.AtomicReference<Value>(typed);
        EffectiveCapabilityResolver resolver = request -> new EffectiveCapabilityResolver.Resolution("r1",
                new EffectiveCapabilityResolver.Context(1, request.scope(), request.evaluatedAt(),
                        request.evaluatedAt().plusSeconds(60), "r1", List.of(new TypedCapability<>(CAN_READ, capabilityValue.get()))));
        var meters = new SimpleMeterRegistry();
        try (var cedar = new CedarAuthoriser(schema, policies, List.of(model), "boundary", "c".repeat(64),
                List.of(bindings), List.of(resolver), Clock.systemUTC(), backend, meters)) {
            var reads = new ScopedAuthorisedReads(source(), cedar, List.of(bindings), meters);
            AuthorisedDisclosures disclosures = new FencedDisclosures(reads, Clock.systemUTC());
            var scope = new TypedSubject<>(SCOPE, "scope");
            var request = new ReadRequest<>("tenant", new Principal("User", "u"), scope, projection, Set.of(display),
                    "boundary", "c".repeat(64), new ReadLimits(2, 2, 4096, 5000));
            var rowValue = new java.util.concurrent.atomic.AtomicReference<Value>(typed);
            var increment = new java.util.concurrent.atomic.AtomicLong();
            var rows = new ReadSource<Row>() {
                public String revision(java.sql.Connection connection) { return "source-1"; }
                public void select(java.sql.Connection connection, java.util.function.Consumer<ReadCandidate<Row>> sink) {
                    sink.accept(new ReadCandidate<>("tenant", scope, new TypedSubject<>(ROW, "row"),
                            new Row(rowValue.get(), increment.get())));
                }
            };
            assertThat(reads.many(request, rows)).hasSize(1);
            assertThat(accessed.get()).isEqualTo(1);
            var received = new ArrayList<String>();
            var released = new ArrayList<String>();
            AuthorisedDisclosures.Formatter<Row> formatter = (views, output) -> {
                assertThat(views).hasSize(1);
                output.write(views.getFirst().get(display).value().getBytes(StandardCharsets.UTF_8));
            };
            AuthorisedDisclosures.Provider provider = (context, output) -> {
                received.add(new String(context.copy(), StandardCharsets.UTF_8));
                output.write("answer".getBytes(StandardCharsets.UTF_8));
            };
            AuthorisedDisclosures.Sink sink = bytes -> released.add(new String(bytes.copy(), StandardCharsets.UTF_8));
            var export = disclosures.prepare(request, rows, "export", Duration.ofMinutes(1));
            var assistant = disclosures.prepare(request, rows, "assistant", Duration.ofMinutes(1));
            disclosures.send(export, request, "export", rows, formatter, sink);
            var answer = disclosures.derive(assistant, request, "assistant", rows, formatter, provider);
            disclosures.send(answer, request, "assistant", rows, sink);
            assertThat(received).containsExactly("protected __entity __extn");
            assertThat(released).containsExactly("protected __entity __extn", "answer");
            received.clear();
            released.clear();
            accessed.set(0);
            Runnable assertDenied = () -> {
                for (Runnable release : List.<Runnable>of(
                        () -> disclosures.prepare(request, rows, "export", Duration.ofMinutes(1)),
                        () -> disclosures.send(export, request, "export", rows, formatter, sink),
                        () -> disclosures.derive(assistant, request, "assistant", rows, formatter, provider),
                        () -> disclosures.send(answer, request, "assistant", rows, sink),
                        () -> reads.many(request, rows))) {
                    assertThatThrownBy(release::run).isExactlyInstanceOf(AuthorisationDeniedException.class)
                            .hasMessage("Authorisation denied").hasNoCause();
                }
                assertThat(received).isEmpty();
                assertThat(released).isEmpty();
                assertThat(accessed.get()).isZero();
            };
            Runnable reset = () -> {
                rowValue.set(typed);
                capabilityValue.set(typed);
                increment.set(0);
            };
            java.util.function.Consumer<Runnable> assertLateDenied = corrupt -> {
                reset.run();
                AuthorisedDisclosures.Formatter<Row> changedDuringFormatting = (views, output) -> {
                    formatter.format(views, output);
                    corrupt.run();
                };
                assertThatThrownBy(() -> disclosures.send(export, request, "export", rows, changedDuringFormatting, sink))
                        .isExactlyInstanceOf(AuthorisationDeniedException.class);
                assertThat(released).isEmpty();
                reset.run();
                assertThatThrownBy(() -> disclosures.derive(assistant, request, "assistant", rows, changedDuringFormatting, provider))
                        .isExactlyInstanceOf(AuthorisationDeniedException.class);
                assertThat(received).isEmpty();
                reset.run();
                var changedAfterProvider = disclosures.derive(assistant, request, "assistant", rows, formatter, (context, output) -> {
                    provider.exchange(context, output);
                    corrupt.run();
                });
                assertThat(received).containsExactly("protected __entity __extn");
                assertThatThrownBy(() -> disclosures.send(changedAfterProvider, request, "assistant", rows, sink))
                        .isExactlyInstanceOf(AuthorisationDeniedException.class);
                assertThat(released).isEmpty();
                received.clear();
                reset.run();
                accessed.set(0);
            };
            for (boolean explicit : List.of(true, false)) {
                try {
                    var body = valueType.equals("User")
                            ? new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u")))
                            : new CedarMap(Map.of("fn", new PrimString("decimal"), "arg", new PrimString("1.0")));
                    var fake = explicit ? new CedarMap(Map.of(valueType.equals("User") ? "__entity" : "__extn", body)) : body;
                    var target = inCapability ? capabilityValue : rowValue;
                    target.set(fake);
                    assertDenied.run();
                    assertLateDenied.accept(() -> target.set(fake));
                } catch (IllegalArgumentException rejectedAtConstruction) {
                    // Patched SDK admission rejects this unsupported input before a read can start.
                }
            }
            rowValue.set(typed);
            capabilityValue.set(typed);
            increment.set(1);
            assertDenied.run();
            assertLateDenied.accept(() -> increment.set(1));
        }
    }

    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private record Row(Value data, long increment) {}
}
