package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.TypedActionOffer;
import io.github.gmcnicol.kernel.application.TypedPresentationEnvelope;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.RecordInteractionCandidateV1;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.TypedCrmActions;
import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.util.HtmlUtils;

final class CrmPresentation {

    private CrmPresentation() {}

    static TypedPresentationPack<ContactId, FollowUpProjection> typedDesktop() {
        return envelope -> render(envelope, false);
    }

    static TypedPresentationPack<ContactId, FollowUpProjection> typedMobile() {
        return envelope -> render(envelope, true);
    }

    static String contacts(List<CrmContactQueries.ContactSummary> contacts, CsrfToken csrf) {
        String rows = contacts.isEmpty()
                ? "<p>No contacts yet.</p>"
                : "<ul>" + contacts.stream().map(contact -> "<li><a href=\"/contacts/"
                        + escape(contact.contactId()) + "\">" + escape(contact.displayName()) + "</a>"
                        + " <span>" + (contact.complete() ? "Follow-up complete" : "Follow-up due")
                        + "</span></li>").collect(Collectors.joining()) + "</ul>";
        return "<main id=\"contacts\"><h1>Keep in touch</h1>"
                + "<form method=\"post\" action=\"/contacts\">"
                + "<input type=\"hidden\" name=\"" + escape(csrf.getParameterName()) + "\" value=\""
                + escape(csrf.getToken()) + "\">"
                + "<label>Name <input name=\"displayName\" maxlength=\"200\" required></label>"
                + "<label>Follow up at (UTC) <input type=\"datetime-local\" name=\"nextContactDueAt\" required></label>"
                + "<button type=\"submit\">Add contact</button></form>" + rows + "</main>";
    }

    static String shell(String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>Keep in touch</title>"
                + "<script type=\"module\" "
                + "src=\"https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js\" "
                + "integrity=\"sha384-SnyFlWTdFL3c8+9/1WsPuMFBq6AQOGC1LmS9upY4YkM3En3wZr5q2UvydHaMgOVG\" "
                + "crossorigin=\"anonymous\"></script></head><body>" + body + "</body></html>";
    }

    private static PresentationResult render(
            TypedPresentationEnvelope<ContactId, FollowUpProjection> envelope, boolean mobile) {
        String name = escape(envelope.field(FollowUpProjection.CONTACT_ID)
                .map(ContactId::value).orElse(envelope.subject().externalId()));
        String controls = envelope.actionOffers().stream().map(CrmPresentation::typedControl)
                .collect(Collectors.joining());
        String html = "<nav><a href=\"/contacts\">Contacts</a></nav>" + (mobile
                ? "<main id=\"crm-mobile\"><p>Next relationship</p><h1>" + name
                        + "</h1><section class=\"thumb-actions\">" + controls + "</section></main>"
                : "<main id=\"crm-desktop\"><header><p>Relationship workspace</p><h1>" + name
                        + "</h1></header><aside>Follow-up facts: " + envelope.facts().size()
                        + "</aside><section class=\"action-bar\">" + controls + "</section></main>");
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

    private static String patch(String html) {
        return "event: datastar-patch-elements\ndata: elements "
                + html.replace("\n", "\ndata: elements ") + "\n\n";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value);
    }
}
