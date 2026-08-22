package io.github.gmcnicol.kernel.semanticpack;

import io.github.gmcnicol.kernel.application.ActionType;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.TypedStateTransition;
import java.util.List;

/** Pure typed handler bound by one generated Action descriptor. */
public interface TypedIntentHandler<P, C, E> {
    ActionType<P, C, E> actionType();
    List<TypedStateTransition<P, E>> handle(Intent intent, C payload, P projection);

    @FunctionalInterface
    interface Handling<P, C, E> {
        List<TypedStateTransition<P, E>> handle(Intent intent, C payload, P projection);
    }
}
