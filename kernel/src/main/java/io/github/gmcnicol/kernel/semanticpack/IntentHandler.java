package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.ProjectedState;
import java.util.List;

/** Pure Application-owned handler for one qualified Action. */
public interface IntentHandler extends SemanticImplementation {

    List<Event> handle(Intent intent, CandidatePayload payload, ProjectedState state);

    @Override
    default Kind kind() {
        return Kind.HANDLER;
    }

    static IntentHandler of(String target, Handling handling) {
        return new Binding(target, handling);
    }

    @FunctionalInterface
    interface Handling {
        List<Event> handle(Intent intent, CandidatePayload payload, ProjectedState state);
    }

    record Binding(String target, Handling handling) implements IntentHandler {

        public Binding {
            if (target == null || target.isBlank() || handling == null) {
                throw new IllegalArgumentException("Intent handler requires target and handling");
            }
        }

        @Override
        public List<Event> handle(Intent intent, CandidatePayload payload, ProjectedState state) {
            return handling.handle(intent, payload, state);
        }
    }
}
