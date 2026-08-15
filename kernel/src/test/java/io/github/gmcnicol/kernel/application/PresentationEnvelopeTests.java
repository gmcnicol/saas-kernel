package io.github.gmcnicol.kernel.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PresentationEnvelopeTests {

    @Test
    void exposesOnlyReducedAuthorisedSemantics() {
        var envelope = new PresentationEnvelope(
                1,
                new Subject("test.Entity", "one"),
                UUID.randomUUID(),
                Instant.parse("2026-08-15T10:00:00Z"),
                "test.semantic",
                Map.of("test.Entity.name", "Ada"),
                java.util.List.of(new PresentationFact("test.SampleFact", Map.of("value", "due"))),
                java.util.List.of(new PresentationActionOffer(
                        UUID.randomUUID(), "test.Actions.act", "test.SampleInput")));

        assertThat(envelope.fields()).containsOnlyKeys("test.Entity.name");
        assertThat(envelope.facts()).extracting(PresentationFact::type).containsExactly("test.SampleFact");
        assertThat(envelope.actionOffers()).extracting(PresentationActionOffer::inputType)
                .containsExactly("test.SampleInput");
        Set<String> components = Arrays.stream(PresentationEnvelope.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertThat(components).doesNotContain(
                "principal", "applicableActions", "stateVersion", "semanticPackChecksum", "cedarEvidence");
    }
}
