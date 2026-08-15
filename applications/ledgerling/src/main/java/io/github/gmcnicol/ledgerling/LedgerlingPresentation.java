package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.application.PresentationActionOffer;
import io.github.gmcnicol.kernel.application.PresentationEnvelope;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

final class LedgerlingPresentation {

    private static final Map<String, String> LABELS = Map.of(
            "recordRecordsReceived", "Record records received",
            "startPreparation", "Start preparation");

    private LedgerlingPresentation() {}

    static PresentationPack defaultPack() {
        return PresentationPack.of("presentation/default.properties", LedgerlingPresentation::render);
    }

    private static PresentationResult render(PresentationEnvelope envelope) {
        String status = escape(envelope.fields().getOrDefault(
                "io.github.gmcnicol.ledgerling.Filing.status", "Filing"));
        String controls = envelope.actionOffers().stream()
                .map(LedgerlingPresentation::control)
                .collect(Collectors.joining());
        String html = "<main id=\"ledgerling-work-queue\"><header><p>Filing operations</p><h1>"
                + status + "</h1></header><dl><dt>Open facts</dt><dd>" + envelope.facts().size()
                + "</dd></dl><section class=\"work-controls\">" + controls + "</section></main>";
        return new PresentationResult(html, patch(html), envelope.actionOffers().stream()
                .map(PresentationActionOffer::id).collect(Collectors.toSet()));
    }

    private static String control(PresentationActionOffer offer) {
        String action = offer.actionId().substring(offer.actionId().lastIndexOf('.') + 1);
        String field = action.equals("recordRecordsReceived")
                ? "<input name=\"receivedAt\" required>"
                : "<input type=\"hidden\" name=\"confirmed\" value=\"true\">";
        return "<form method=\"post\" action=\"/presentation/intents/" + offer.id() + "\">"
                + "<input type=\"hidden\" name=\"intentId\" value=\"" + UUID.randomUUID() + "\">"
                + "<input type=\"hidden\" name=\"payloadType\" value=\""
                + escape(offer.inputType()) + "\">"
                + "<input type=\"hidden\" name=\"payloadVersion\" value=\"1\">" + field
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
