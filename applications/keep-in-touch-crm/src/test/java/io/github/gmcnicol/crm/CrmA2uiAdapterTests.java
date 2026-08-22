package io.github.gmcnicol.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import io.github.gmcnicol.kernel.application.TypedActionOffer;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.application.TypedSubject;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CrmA2uiAdapterTests {

    private static final UUID OFFER_ID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
    private final CrmA2uiAdapter adapter = new CrmA2uiAdapter(new ObjectMapper(), ObservationRegistry.NOOP);

    @Test
    void validatesAndRendersOnlyCurrentOpaqueOffer() {
        assertThat(CrmA2uiAdapter.CATALOGUE)
                .isEqualTo("https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json");
        var rendered = adapter.render(envelope(), messages(OFFER_ID, "Text", "/title"));

        assertThat(rendered.html())
                .contains("Alex &lt;script&gt;", "Record &lt;now&gt;", "/presentation/intents/" + OFFER_ID,
                        "data-on:submit", "io.github.gmcnicol.crm.RecordInteractionCandidateV1", "Talk &lt;soon&gt;")
                .doesNotContain("Alex <script>", "Record <now>");
        assertThat(rendered.eventStream()).startsWith("event: datastar-patch-elements\n");
        assertThat(rendered.eventStream()).doesNotContain("\nevent: forged");
        assertThat(rendered.renderedActionOffers()).containsExactly(OFFER_ID);
    }

    @Test
    void rejectsMalformedSurfaceBeforeProducingControls() {
        assertThatThrownBy(() -> adapter.render(envelope(), messages(OFFER_ID, "Video", "/title")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), messages(UUID.randomUUID(), "Text", "/title")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), messages(OFFER_ID, "Text", "/missing")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), messages(OFFER_ID, "Text", "/title")
                        .replace("v0.9.1", "v1.0.0")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), messages(OFFER_ID, "Text", "/title")
                        .replace(CrmA2uiAdapter.CATALOGUE, "https://example.test/other.json")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
    }

    @Test
    void rejectsBadOrderSurfaceReferencesCyclesAndEvents() {
        String valid = messages(OFFER_ID, "Text", "/title");
        assertThatThrownBy(() -> adapter.render(envelope(), valid.replaceFirst("createSurface", "updateDataModel")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), valid.replaceFirst("follow-up", "other")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), valid.replace("[\"title\",\"button\"]", "[\"missing\"]")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), valid.replace("[\"title\",\"button\"]", "[\"root\"]")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), valid.replace("invokeActionOffer", "inventAction")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), valid.replace("\"id\":\"root\"", "\"id\":\"page\"")))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
    }

    @Test
    void rejectsNestedButtonsAndAmplifiedOutput() {
        assertThatThrownBy(() -> adapter.render(envelope(), nestedButtons(false)))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
        assertThatThrownBy(() -> adapter.render(envelope(), nestedButtons(true)))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);

        String fields = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> "\"field" + index + "\":{\"path\":\"/note\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String amplified = messages(OFFER_ID, "Text", "/title")
                .replace("\"note\":{\"path\":\"/note\"}", fields)
                .replace("Talk <soon>", "x".repeat(4_096));
        assertThatThrownBy(() -> adapter.render(envelope(), amplified))
                .isInstanceOf(CrmA2uiAdapter.InvalidSurface.class);
    }

    private static TypedPresentationEnvelope<ContactId, FollowUpProjection> envelope() {
        return new TypedPresentationEnvelope<>(
                1,
                new TypedSubject<>(ContactId.TYPE, new ContactId("alex")),
                FollowUpProjection.TYPE,
                UUID.randomUUID(),
                Instant.parse("2026-08-15T10:00:00Z"),
                "io.github.gmcnicol.crm.semantic",
                List.of(),
                List.of(),
                List.of(new TypedActionOffer<>(OFFER_ID, TypedCrmActions.RECORD_INTERACTION)));
    }

    private static String messages(UUID offerId, String textComponent, String path) {
        return """
                [
                  {"version":"v0.9.1","createSurface":{"surfaceId":"follow-up","catalogId":"%s"}},
                  {"version":"v0.9.1","updateComponents":{"surfaceId":"follow-up","components":[
                    {"id":"root","component":"Column","children":["title","button"]},
                    {"id":"title","component":"%s","text":{"path":"%s"}},
                    {"id":"label","component":"Text","text":"Record <now>"},
                    {"id":"button","component":"Button","child":"label","action":{
                      "event":{"name":"invokeActionOffer","context":{"actionOfferId":"%s",
                        "note":{"path":"/note"}
                      }}
                    }}
                  ]}},
                  {"version":"v0.9.1","updateDataModel":{
                    "surfaceId":"follow-up","path":"/","value":{"title":"Alex <script>\\nevent: forged","note":"Talk <soon>"}
                  }}
                ]
                """.formatted(CrmA2uiAdapter.CATALOGUE, textComponent, path, offerId);
    }

    private static String nestedButtons(boolean throughColumn) {
        String middle = throughColumn
                ? "{\"id\":\"middle\",\"component\":\"Column\",\"children\":[\"inner\"]},"
                : "";
        String child = throughColumn ? "middle" : "inner";
        return """
                [
                  {"version":"v0.9.1","createSurface":{"surfaceId":"follow-up","catalogId":"%s"}},
                  {"version":"v0.9.1","updateComponents":{"surfaceId":"follow-up","components":[
                    {"id":"root","component":"Button","child":"%s","action":{"event":{"name":"invokeActionOffer","context":{"actionOfferId":"%s"}}}},
                    %s
                    {"id":"inner","component":"Button","child":"label","action":{"event":{"name":"invokeActionOffer","context":{"actionOfferId":"%s"}}}},
                    {"id":"label","component":"Text","text":"Label"}
                  ]}},
                  {"version":"v0.9.1","updateDataModel":{"surfaceId":"follow-up","path":"/","value":{}}}
                ]
                """.formatted(CrmA2uiAdapter.CATALOGUE, child, OFFER_ID, middle, OFFER_ID);
    }
}
