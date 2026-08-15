package io.github.gmcnicol.crm;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import io.github.gmcnicol.kernel.presentationpack.PresentationResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    CrmPresentationController(
            Kernel kernel,
            @Qualifier("crmDesktopPresentationPack") PresentationPack desktop,
            @Qualifier("crmMobilePresentationPack") PresentationPack mobile) {
        this.kernel = kernel;
        this.desktop = desktop;
        this.mobile = mobile;
    }

    @GetMapping(path = "/presentation/crm/{experience}", produces = MediaType.TEXT_HTML_VALUE)
    String html(
            @PathVariable String experience,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Principal-Type") String principalType,
            @RequestHeader("X-Principal-Id") String principalId,
            @RequestParam UUID snapshotId,
            @RequestParam Instant at) {
        return render(experience, tenantId, principalType, principalId, snapshotId, at).html();
    }

    @GetMapping(path = "/presentation/crm/{experience}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    String events(
            @PathVariable String experience,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Principal-Type") String principalType,
            @RequestHeader("X-Principal-Id") String principalId,
            @RequestParam UUID snapshotId,
            @RequestParam Instant at) {
        return render(experience, tenantId, principalType, principalId, snapshotId, at).eventStream();
    }

    @PostMapping(
            path = "/presentation/intents/{offerId}",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<String> invoke(@PathVariable UUID offerId, @RequestParam MultiValueMap<String, String> form) {
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
        var intent = kernel.accept(offerId, intentId, new CandidatePayload(payloadType, payloadVersion, values));
        String html = "<section id=\"intent-result\"><p>Intent " + intent.id()
                + " accepted: " + intent.status() + "</p></section>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: datastar-patch-elements\ndata: elements " + html + "\n\n");
    }

    private PresentationResult render(
            String experience,
            String tenantId,
            String principalType,
            String principalId,
            UUID snapshotId,
            Instant at) {
        PresentationPack pack = switch (experience) {
            case "desktop" -> desktop;
            case "mobile" -> mobile;
            default -> throw new IllegalArgumentException("Unknown CRM presentation experience");
        };
        return pack.render(kernel.present(tenantId, snapshotId, new Principal(principalType, principalId), at));
    }

    private static String single(MultiValueMap<String, String> form, String name) {
        var values = form.get(name);
        if (values == null || values.size() != 1) throw new IllegalArgumentException("Missing or duplicate " + name);
        return values.getFirst();
    }
}
