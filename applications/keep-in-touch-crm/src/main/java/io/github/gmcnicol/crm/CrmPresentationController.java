package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class CrmPresentationController {

    private final Kernel kernel;
    private final PresentationPack desktop;
    private final PresentationPack mobile;
    private final Clock clock;

    CrmPresentationController(
            Kernel kernel,
            @Qualifier("crmDesktopPresentationPack") PresentationPack desktop,
            @Qualifier("crmMobilePresentationPack") PresentationPack mobile,
            Clock clock) {
        this.kernel = kernel;
        this.desktop = desktop;
        this.mobile = mobile;
        this.clock = clock;
    }

    @GetMapping(path = "/presentation/crm/{experience}", produces = MediaType.TEXT_HTML_VALUE)
    String html(
            @PathVariable String experience,
            Authentication authentication,
            @RequestParam UUID snapshotId) {
        return shell(render(experience, caller(authentication), snapshotId).html());
    }

    @GetMapping(path = "/presentation/crm/{experience}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    String events(
            @PathVariable String experience,
            Authentication authentication,
            @RequestParam UUID snapshotId) {
        return render(experience, caller(authentication), snapshotId).eventStream();
    }

    @PostMapping(
            path = "/presentation/intents/{offerId}",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<String> invoke(
            @PathVariable UUID offerId,
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(name = "traceparent", required = false) String traceparent,
            @RequestHeader(name = "tracestate", required = false) String tracestate) {
        UUID intentId = UUID.fromString(single(form, "intentId"));
        String payloadType = single(form, "payloadType");
        int payloadVersion = Integer.parseInt(single(form, "payloadVersion"));
        Map<String, String> values = new LinkedHashMap<>();
        form.forEach((name, entries) -> {
            if (!name.equals("intentId") && !name.equals("payloadType") && !name.equals("payloadVersion")) {
                if (entries.size() != 1) throw new IllegalArgumentException("Duplicate payload field");
                values.put(name, entries.getFirst());
            }
        });
        var intent = kernel.accept(offerId, intentId, new CandidatePayload(
                payloadType, payloadVersion, values, trace(traceparent, tracestate), Optional.empty()));
        String html = "<section id=\"intent-result\"><p>Intent " + intent.id()
                + " accepted: " + intent.status() + "</p></section>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: datastar-patch-elements\ndata: elements " + html + "\n\n");
    }

    private PresentationResult render(
            String experience,
            Caller caller,
            UUID snapshotId) {
        PresentationPack pack = switch (experience) {
            case "desktop" -> desktop;
            case "mobile" -> mobile;
            default -> throw new IllegalArgumentException("Unknown CRM presentation experience");
        };
        return pack.render(kernel.present(
                caller.tenantId(), snapshotId, new Principal(caller.principalType(), caller.principalId()),
                Instant.now(clock)));
    }

    private static Caller caller(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated caller required");
        }
        String tenant = authority(authentication, "ROLE_TENANT_");
        String type = authority(authentication, "ROLE_PRINCIPAL_");
        return new Caller(tenant, type, authentication.getName());
    }

    private static String authority(Authentication authentication, String prefix) {
        var values = authentication.getAuthorities().stream().map(Object::toString)
                .filter(value -> value.startsWith(prefix)).toList();
        if (values.size() != 1 || values.getFirst().length() == prefix.length()) {
            throw new AccessDeniedException("Exactly one " + prefix + " authority required");
        }
        return values.getFirst().substring(prefix.length());
    }

    private record Caller(String tenantId, String principalType, String principalId) {}

    private static String shell(String body) {
        return "<!doctype html><html><head><script type=\"module\" "
                + "src=\"https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js\" "
                + "integrity=\"sha384-SnyFlWTdFL3c8+9/1WsPuMFBq6AQOGC1LmS9upY4YkM3En3wZr5q2UvydHaMgOVG\" "
                + "crossorigin=\"anonymous\">"
                + "</script></head><body>" + body + "</body></html>";
    }

    private static String single(MultiValueMap<String, String> form, String name) {
        var values = form.get(name);
        if (values == null || values.size() != 1) throw new IllegalArgumentException("Missing or duplicate " + name);
        return values.getFirst();
    }

    private static Optional<W3cTraceContext> trace(String traceparent, String tracestate) {
        return traceparent == null && tracestate == null
                ? Optional.empty()
                : Optional.of(new W3cTraceContext(traceparent, tracestate));
    }
}
