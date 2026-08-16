package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.PresentationActionOffer;
import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.github.gmcnicol.kernel.application.TypedActionOffer;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

final class CrmPresentation {

    private static final Map<String, String> LABELS = Map.of(
            "recordInteraction", "Record interaction",
            "snoozeFollowUp", "Snooze follow-up",
            "completeFollowUp", "Complete follow-up");

    private CrmPresentation() {}

    static PresentationPack desktop() {
        return PresentationPack.of("presentation/desktop.properties", envelope -> render(envelope, false));
    }

    static PresentationPack mobile() {
        return PresentationPack.of("presentation/mobile.properties", envelope -> render(envelope, true));
    }

    static TypedPresentationPack<ContactId, FollowUpProjection> typedDesktop() {
        return envelope -> render(envelope, false);
    }

    static TypedPresentationPack<ContactId, FollowUpProjection> typedMobile() {
        return envelope -> render(envelope, true);
    }

    private static PresentationResult render(
            TypedPresentationEnvelope<ContactId, FollowUpProjection> envelope, boolean mobile) {
        String name = escape(envelope.field(FollowUpProjection.CONTACT_ID)
                .map(ContactId::value).orElse(envelope.subject().externalId()));
        String controls = envelope.actionOffers().stream().map(CrmPresentation::typedControl)
                .collect(Collectors.joining());
        String html = mobile
                ? "<main id=\"crm-mobile\"><p>Next relationship</p><h1>" + name
                        + "</h1><section class=\"thumb-actions\">" + controls + "</section></main>"
                : "<main id=\"crm-desktop\"><header><p>Relationship workspace</p><h1>" + name
                        + "</h1></header><aside>Follow-up facts: " + envelope.facts().size()
                        + "</aside><section class=\"action-bar\">" + controls + "</section></main>";
        return new PresentationResult(html, patch(html), envelope.actionOffers().stream()
                .map(TypedActionOffer::id).collect(Collectors.toSet()));
    }

    private static String typedControl(TypedActionOffer<FollowUpProjection, ?, ?> offer) {
        if (offer.actionType() != TypedCrmActions.RECORD_INTERACTION) return "";
        return "<form method=\"post\" action=\"/presentation/intents/" + offer.id()
                + "\" data-on:submit=\"@post('/presentation/intents/" + offer.id()
                + "', {contentType: 'form'})\">"
                + "<input type=\"hidden\" name=\"intentId\" value=\"" + UUID.randomUUID() + "\">"
                + "<input type=\"hidden\" name=\"actionType\" value=\""
                + escape(offer.actionType().qualifiedName()) + "\">"
                + "<input type=\"hidden\" name=\"payloadType\" value=\""
                + escape(offer.actionType().candidateType().qualifiedName()) + "\">"
                + "<input type=\"hidden\" name=\"payloadVersion\" value=\""
                + offer.actionType().candidateType().contractVersion() + "\">"
                + "<input name=\"" + RecordInteractionCandidateV1.NOTE.name() + "\" required>"
                + "<button type=\"submit\">Record interaction</button></form>";
    }

    private static PresentationResult render(PresentationEnvelope envelope, boolean mobile) {
        String name = escape(envelope.fields().getOrDefault(
                "io.github.gmcnicol.crm.Contact.displayName", envelope.subject().id()));
        String controls = envelope.actionOffers().stream().map(CrmPresentation::control).collect(Collectors.joining());
        String html = mobile
                ? "<main id=\"crm-mobile\"><p>Next relationship</p><h1>" + name
                        + "</h1><section class=\"thumb-actions\">" + controls + "</section></main>"
                : "<main id=\"crm-desktop\"><header><p>Relationship workspace</p><h1>" + name
                        + "</h1></header><aside>Follow-up facts: " + envelope.facts().size()
                        + "</aside><section class=\"action-bar\">" + controls + "</section></main>";
        return new PresentationResult(html, patch(html), envelope.actionOffers().stream()
                .map(PresentationActionOffer::id).collect(Collectors.toSet()));
    }

    private static String control(PresentationActionOffer offer) {
        String action = offer.actionId().substring(offer.actionId().lastIndexOf('.') + 1);
        String field = switch (action) {
            case "recordInteraction", "completeFollowUp" -> "<input name=\"note\" required>";
            case "snoozeFollowUp" -> "<input name=\"until\" required>";
            default -> "";
        };
        return "<form method=\"post\" action=\"/presentation/intents/" + offer.id()
                + "\" data-on:submit=\"@post('/presentation/intents/" + offer.id()
                + "', {contentType: 'form'})\">"
                + "<input type=\"hidden\" name=\"intentId\" value=\"" + UUID.randomUUID() + "\">"
                + "<input type=\"hidden\" name=\"payloadType\" value=\"" + escape(offer.inputType()) + "\">"
                + "<input type=\"hidden\" name=\"payloadVersion\" value=\"2\">" + field
                + "<button type=\"submit\">" + escape(LABELS.getOrDefault(action, action)) + "</button></form>";
    }

    private static String patch(String html) {
        return "event: datastar-patch-elements\ndata: elements "
                + html.replace("\n", "\ndata: elements ") + "\n\n";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
