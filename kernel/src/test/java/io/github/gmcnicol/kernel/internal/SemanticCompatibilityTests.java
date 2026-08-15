package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticCompatibilityTests {

    private final SemanticCompatibility compatibility = new SemanticCompatibility(List.of(
            SemanticVersionAdapter.identity(
                    SemanticVersionAdapter.Contract.PAYLOAD, "example.Input", 1, 2),
            SemanticVersionAdapter.identity(
                    SemanticVersionAdapter.Contract.EVENT, "example.Happened", 1, 2)));

    @Test
    void readsHistoricalPayloadAndEventThroughExplicitForwardAdapters() {
        CandidatePayload payload = compatibility.adapt(
                new CandidatePayload("example.Input", 1, Map.of("value", "old")));
        Event event = compatibility.adapt(
                new Event("example.Happened", 1, Map.of("value", "old"), Map.of("state", "current")));

        assertThat(payload.version()).isEqualTo(2);
        assertThat(payload.values()).containsEntry("value", "old");
        assertThat(event.version()).isEqualTo(2);
        assertThat(event.payload()).containsEntry("value", "old");
        assertThat(event.resultingState()).containsEntry("state", "current");
        assertThatThrownBy(() -> compatibility.adapt(
                        new CandidatePayload("example.Input", 3, Map.of("value", "unknown"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
