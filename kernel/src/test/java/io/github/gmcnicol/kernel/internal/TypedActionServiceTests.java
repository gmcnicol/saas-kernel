package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.CandidateType;
import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.EventType;
import io.github.gmcnicol.kernel.application.ProjectionType;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.SubjectType;
import io.github.gmcnicol.kernel.semanticpack.SemanticBindings;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

class TypedActionServiceTests {

    @Test
    void requiresGeneratedHandlerAndProjectorBindingsAtStartup() {
        var subject = new SubjectType<>("test.Id", Id.class, Id::value, Id::new);
        var projection = new ProjectionType<>("test.State", 1, subject, State.class, List.of());
        var candidate = new CandidateType<>("test.Input", 1, Input.class);
        var event = new EventType<>("test.Changed", 1, Changed.class);
        var action = new ActionType<>("test.Actions.change", projection, candidate, List.of(event));
        var policy = action.bindApplicability((state, facts) -> true);
        var bindings = List.of(new SemanticBindings(
                List.of(projection), List.of(), List.of(candidate), List.of(event), List.of(action)));

        assertThatThrownBy(() -> service(bindings, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Generated Action requires exactly one applicability binding: test.Actions.change");

        assertThatThrownBy(() -> service(bindings, List.of(), List.of(), List.of(policy, policy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Generated Action requires exactly one applicability binding: test.Actions.change");

        assertThatThrownBy(() -> service(bindings, List.of(), List.of(), List.of(policy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unregistered generated handler Action");

        assertThatThrownBy(() -> service(bindings, List.of(action.bindHandler((intent, input, state) -> List.of())),
                        List.of(), List.of(policy)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unregistered generated projector Event");
    }

    private static TypedActionService service(
            List<SemanticBindings> bindings,
            List<io.github.gmcnicol.kernel.semanticpack.TypedIntentHandler<?, ?, ?>> handlers,
            List<io.github.gmcnicol.kernel.semanticpack.TypedEventProjector<?, ?>> projectors,
            List<io.github.gmcnicol.kernel.semanticpack.TypedApplicabilityPolicy<?>> policies) {
        return new TypedActionService(
                mock(JdbcTemplate.class), mock(TransactionOperations.class), mock(CedarAuthoriser.class),
                new IntentWorkerProperties(), Clock.systemUTC(), mock(KernelTelemetry.class),
                new SemanticPackVersion("test", "checksum"),
                bindings, handlers, projectors, policies, List.of(), CanonicalCodec.Limits.defaults());
    }

    private record Id(String value) {}
    private record State() {}
    private record Input() {}
    private record Changed() {}
}
