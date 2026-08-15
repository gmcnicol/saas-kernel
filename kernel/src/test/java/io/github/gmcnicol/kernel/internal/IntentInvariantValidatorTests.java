package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.IntentStatus;
import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IntentInvariantValidatorTests {

    private final IntentInvariantValidator invariants = new IntentInvariantValidator();

    @Test
    void acceptsFixedAndLongValidSequencesAndRejectsImpossibleOnes() {
        invariants.transition(IntentStatus.PENDING, IntentStatus.CLAIMED);
        for (int attempt = 0; attempt < 1_000; attempt++) {
            invariants.transition(IntentStatus.CLAIMED, IntentStatus.RETRY_WAIT);
            invariants.transition(IntentStatus.RETRY_WAIT, IntentStatus.CLAIMED);
        }
        invariants.transition(IntentStatus.CLAIMED, IntentStatus.SUCCEEDED);
        invariants.eventSequence(IntStream.rangeClosed(1, 1_000).boxed().toList());

        assertThatThrownBy(() -> invariants.transition(IntentStatus.SUCCEEDED, IntentStatus.CLAIMED))
                .isInstanceOf(FatalInvariantError.class);
        assertThatThrownBy(() -> invariants.eventSequence(java.util.List.of(1, 3)))
                .isInstanceOf(FatalInvariantError.class);
    }

    @Test
    void rejectsInvalidWorkerPolicyValues() {
        var policy = new IntentWorkerProperties();
        assertThatThrownBy(() -> policy.setLeaseDuration(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setMaximumAttempts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setRetryBackoff(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setClaimBatchSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setPollingInterval(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
