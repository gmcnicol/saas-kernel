package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.IntentStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IntentInvariantValidator {

    private static final Map<IntentStatus, Set<IntentStatus>> TRANSITIONS = Map.of(
            IntentStatus.PENDING, Set.of(IntentStatus.CLAIMED),
            IntentStatus.RETRY_WAIT, Set.of(IntentStatus.CLAIMED),
            IntentStatus.CLAIMED, Set.of(
                    IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT, IntentStatus.SUCCEEDED,
                    IntentStatus.STALE, IntentStatus.FAILED),
            IntentStatus.SUCCEEDED, Set.of(),
            IntentStatus.STALE, Set.of(),
            IntentStatus.FAILED, Set.of());

    void transition(IntentStatus from, IntentStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new FatalInvariantError("Invalid Intent transition: " + from + " -> " + to);
        }
    }

    void eventSequence(List<Integer> sequences) {
        for (int index = 0; index < sequences.size(); index++) {
            if (sequences.get(index) != index + 1) {
                throw new FatalInvariantError("Invalid Event sequence at position " + (index + 1));
            }
        }
    }
}
