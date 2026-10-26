package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import io.github.gmcnicol.kernel.application.*;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Public AuthorisedReads mechanism contract; generated assembly identity/RLS are covered by peer fixtures. */
@Testcontainers
@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class ScalarReadOrderTests {
    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final SubjectType<String> ROW = new SubjectType<>("order.RowId", String.class, s -> s);
    static final SubjectType<String> SCOPE = new SubjectType<>("order.Scope", String.class, s -> s);
    record Row(String value) {}
    @BeforeAll static void role() {
        new JdbcTemplate(source()).execute("CREATE ROLE kernel_runtime NOLOGIN NOBYPASSRLS");
    }
    static DriverManagerDataSource source() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }

    @Test void accessorFailureStopsBeforeTheNextFieldDecision() throws Exception { compare("accessor", 2, List.of("a")); }
    @Test void scalarFailureStopsBeforeTheNextFieldDecision() throws Exception { compare("scalar", 2, List.of("a", "encode-a")); }
    @Test void reconstructionFailurePreservesTheOriginalAlreadyExecutedFieldChecks() throws Exception {
        compare("reconstruction", 3, List.of("a", "encode-a", "b", "encode-b", "encode-a"));
    }
    @Test void jsonByteFailureDoesNotReleaseAnEarlierPartialProjection() throws Exception {
        compare("bytes", 3, List.of("a", "encode-a", "b", "encode-b", "encode-a", "encode-b"));
    }
    private void compare(String failure, int decisions, List<String> expected) throws Exception {
        for (String mode : List.of("sdk", "prepared")) {
            var trace = new ArrayList<String>();
            var encoded = new AtomicInteger();
            var a = new ReadPath<Row, String>("order.View.a", failure.equals("bytes") ? "a".repeat(128) : "a", row -> {
                trace.add("a"); if (failure.equals("accessor")) throw new IllegalArgumentException("PRIVATE-ACCESSOR"); return ReadValue.shown(row.value());
            }, value -> {
                trace.add("encode-a");
                if (failure.equals("scalar") || failure.equals("reconstruction") && encoded.incrementAndGet() == 2)
                    throw new IllegalArgumentException("PRIVATE-SCALAR");
                return value;
            });
            var b = new ReadPath<Row, String>("order.View.b", "b", row -> { trace.add("b"); return ReadValue.shown(row.value()); },
                    value -> { trace.add("encode-b"); return value; });
            var projection = new ProjectionType<>("order.View", 1, ROW, Row.class, List.of(), List.of(a, b));
            var cap = new CapabilityType<>("order.CanRead", 1, Boolean.class, List.of(new FieldType<Boolean, Boolean>("order.CanRead.active", v -> v,
                    v -> Optional.of(new com.cedarpolicy.value.PrimBool(v)))));
            var bindings = new SemanticBindings(List.of(projection), List.of(), List.of(), List.of(), List.of(), List.of(cap), List.of(ROW, SCOPE));
            EffectiveCapabilityResolver resolver = request -> new EffectiveCapabilityResolver.Resolution("r1", new EffectiveCapabilityResolver.Context(
                    1, request.scope(), request.evaluatedAt(), request.evaluatedAt().plusSeconds(60), "r1", List.of(new TypedCapability<>(cap, true))));
            var model = new TypedAuthorisationModel<>(projection, Set.of(), Set.of(), Set.of(cap));
            var schema = new Schema("entity User; entity RowId;" + List.of("read", "a", "b").stream()
                    .map(operation -> "action \"typed/v1/order.View." + operation + "\" appliesTo { principal: User, resource: RowId,"
                            + " context: { readScope: String, capabilities: { CanRead: { active: Bool } } } };")
                    .collect(java.util.stream.Collectors.joining()));
            var meters = new SimpleMeterRegistry();
            try (var cedar = new CedarAuthoriser(schema, PolicySet.parsePolicies("permit(principal, action, resource);"), List.of(model),
                    "bundle", "c".repeat(64), List.of(bindings), List.of(resolver), Clock.systemUTC(), mode, meters)) {
                AuthorisedReads reads = new ScopedAuthorisedReads(source(), cedar, List.of(bindings), meters);
                var scope = new TypedSubject<>(SCOPE, "scope");
                var request = new ReadRequest<>("tenant", new Principal("User", "u"), scope, projection, Set.of(a, b),
                        "bundle", "c".repeat(64), new ReadLimits(1, 2, 64, 1000));
                var rows = new ReadSource<Row>() {
                    public String revision(java.sql.Connection c) { return "source-1"; }
                    public void select(java.sql.Connection c, java.util.function.Consumer<ReadCandidate<Row>> sink) {
                        sink.accept(new ReadCandidate<>("tenant", scope, new TypedSubject<>(ROW, "same"), new Row("visible")));
                    }
                };
                assertThatThrownBy(() -> reads.many(request, rows)).isExactlyInstanceOf(AuthorisationDeniedException.class)
                        .hasMessage("Authorisation denied").hasNoCause();
                assertThat(meters.find("kernel.read.authorization.calls").counter().count()).as(mode + ":" + failure).isEqualTo(decisions);
                assertThat(trace).as(mode + ":" + failure).containsExactlyElementsOf(expected);
            }
        }
    }
}
