package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.application.TypedActionOffer;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingId;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingProjection;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.LedgerlingActions;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.RecordRecordsReceivedCandidateV1;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.StartPreparationCandidateV1;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

final class LedgerlingPresentation {
    private LedgerlingPresentation() {}

    static TypedPresentationPack<FilingId, FilingProjection> typed() {
        return envelope -> render(envelope);
    }

    private static PresentationResult render(TypedPresentationEnvelope<FilingId, FilingProjection> envelope) {
        String reference = escape(envelope.field(FilingProjection.CLIENT_REFERENCE)
                .map(value -> value.value()).orElse(envelope.subject().externalId()));
        String controls = envelope.actionOffers().stream()
                .map(LedgerlingPresentation::control).collect(Collectors.joining());
        String html = "<main id=\"ledgerling-work-queue\"><h1>" + reference
                + "</h1><p>Open facts: " + envelope.facts().size() + "</p>" + controls + "</main>";
        return new PresentationResult(html,
                "event: datastar-patch-elements\ndata: elements " + html + "\n\n",
                envelope.actionOffers().stream().map(TypedActionOffer::id).collect(Collectors.toSet()));
    }

    private static String control(TypedActionOffer<FilingProjection, ?, ?> offer) {
        String field;
        String label;
        if (offer.actionType() == LedgerlingActions.RECORD_RECORDS_RECEIVED) {
            field = "<input name=\"" + RecordRecordsReceivedCandidateV1.RECEIVED_AT.name() + "\" required>";
            label = "Record records received";
        } else if (offer.actionType() == LedgerlingActions.START_PREPARATION) {
            field = "<input type=\"hidden\" name=\"" + StartPreparationCandidateV1.CONFIRMED.name()
                    + "\" value=\"true\">";
            label = "Start preparation";
        } else {
            return "";
        }
        return "<form method=\"post\" action=\"/presentation/intents/" + offer.id() + "\">"
                + "<input type=\"hidden\" name=\"intentId\" value=\"" + UUID.randomUUID() + "\">"
                + "<input type=\"hidden\" name=\"actionType\" value=\""
                + escape(offer.actionType().qualifiedName()) + "\">"
                + "<input type=\"hidden\" name=\"payloadType\" value=\""
                + escape(offer.actionType().candidateType().qualifiedName()) + "\">"
                + "<input type=\"hidden\" name=\"payloadVersion\" value=\""
                + offer.actionType().candidateType().contractVersion() + "\">"
                + field + "<button type=\"submit\">" + label + "</button></form>";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
