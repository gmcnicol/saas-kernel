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
import java.time.Clock;
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
    private static final CapabilityType<Value> CAN_READ = new CapabilityType<>("boundary.CanRead", 1, Value.class,
            List.of(new FieldType<Value, Value>("boundary.CanRead.data", value -> value, Optional::of)));

    @BeforeAll static void role() {
        new JdbcTemplate(source()).execute("CREATE ROLE kernel_runtime NOLOGIN NOBYPASSRLS");
    }

    @ParameterizedTest @CsvSource({"sdk,false", "sdk,true", "prepared,false", "prepared,true"})
    void converterAndCapabilityMapsCannotReleaseAProtectedRecord(String backend, boolean inCapability) throws Exception {
        var accessed = new AtomicInteger();
        var display = new ReadPath<Row, String>("boundary.View.display", "display", row -> {
            accessed.incrementAndGet();
            return ReadValue.shown("protected __entity __extn");
        }, value -> value);
        var projection = new ProjectionType<>("boundary.View", 1, ROW, Row.class, List.of(DATA), List.of(display));
        var bindings = new SemanticBindings(List.of(projection), List.of(), List.of(), List.of(), List.of(),
                List.of(CAN_READ), List.of(ROW, SCOPE));
        var model = new TypedAuthorisationModel<>(projection, Set.of(DATA), Set.of(), Set.of(CAN_READ));
        var schema = new Schema("entity User; entity Row { data: User }; "
                + List.of("read", "display").stream().map(operation ->
                        "action \"typed/v1/boundary.View." + operation + "\" appliesTo { principal: User, resource: Row, "
                        + "context: { readScope: String, capabilities: { CanRead: { data: User } } } };")
                        .collect(java.util.stream.Collectors.joining()));
        var policies = PolicySet.parsePolicies("permit(principal, action, resource) when { "
                + "resource.data == principal && context.capabilities.CanRead.data == principal };");
        assertThat(new BasicAuthorizationEngine().validate(new ValidationRequest(schema, policies)).validationPassed()).isTrue();
        Value typed = new EntityUID(EntityTypeName.parse("User").orElseThrow(), "u");
        var capabilityValue = new java.util.concurrent.atomic.AtomicReference<Value>(typed);
        EffectiveCapabilityResolver resolver = request -> new EffectiveCapabilityResolver.Resolution("r1",
                new EffectiveCapabilityResolver.Context(1, request.scope(), request.evaluatedAt(),
                        request.evaluatedAt().plusSeconds(60), "r1", List.of(new TypedCapability<>(CAN_READ, capabilityValue.get()))));
        var meters = new SimpleMeterRegistry();
        try (var cedar = new CedarAuthoriser(schema, policies, List.of(model), "boundary", "c".repeat(64),
                List.of(bindings), List.of(resolver), Clock.systemUTC(), backend, meters)) {
            AuthorisedReads reads = new ScopedAuthorisedReads(source(), cedar, List.of(bindings), meters);
            var scope = new TypedSubject<>(SCOPE, "scope");
            var request = new ReadRequest<>("tenant", new Principal("User", "u"), scope, projection, Set.of(display),
                    "boundary", "c".repeat(64), new ReadLimits(2, 2, 4096, 5000));
            var rowValue = new java.util.concurrent.atomic.AtomicReference<Value>(typed);
            var rows = new ReadSource<Row>() {
                public String revision(java.sql.Connection connection) { return "source-1"; }
                public void select(java.sql.Connection connection, java.util.function.Consumer<ReadCandidate<Row>> sink) {
                    sink.accept(new ReadCandidate<>("tenant", scope, new TypedSubject<>(ROW, "row"), new Row(rowValue.get())));
                }
            };
            assertThat(reads.many(request, rows)).hasSize(1);
            assertThat(accessed.get()).isEqualTo(1);
            accessed.set(0);
            for (boolean explicit : List.of(true, false)) {
                try {
                    var body = new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u")));
                    var fake = explicit ? new CedarMap(Map.of("__entity", body)) : body;
                    (inCapability ? capabilityValue : rowValue).set(fake);
                    assertThatThrownBy(() -> reads.many(request, rows)).isExactlyInstanceOf(AuthorisationDeniedException.class)
                            .hasMessage("Authorisation denied").hasNoCause();
                    assertThat(accessed.get()).isZero();
                } catch (IllegalArgumentException rejectedAtConstruction) {
                    // Patched SDK admission rejects this unsupported input before a read can start.
                }
            }
        }
    }

    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private record Row(Value data) {}
}
