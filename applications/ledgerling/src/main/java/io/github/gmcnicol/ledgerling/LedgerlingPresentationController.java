package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.presentationpack.PresentationPack;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
final class LedgerlingPresentationController {

    private final Kernel kernel;
    private final PresentationPack pack;

    LedgerlingPresentationController(Kernel kernel, PresentationPack pack) {
        this.kernel = kernel;
        this.pack = pack;
    }

    @GetMapping(path = "/presentation/ledgerling", produces = MediaType.TEXT_HTML_VALUE)
    String html(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Principal-Type") String principalType,
            @RequestHeader("X-Principal-Id") String principalId,
            @RequestParam UUID snapshotId,
            @RequestParam Instant at) {
        return render(tenantId, principalType, principalId, snapshotId, at).html();
    }

    @GetMapping(path = "/presentation/ledgerling/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    String events(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Principal-Type") String principalType,
            @RequestHeader("X-Principal-Id") String principalId,
            @RequestParam UUID snapshotId,
            @RequestParam Instant at) {
        return render(tenantId, principalType, principalId, snapshotId, at).eventStream();
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

    private io.github.gmcnicol.kernel.presentationpack.PresentationResult render(
            String tenantId,
            String principalType,
            String principalId,
            UUID snapshotId,
            Instant at) {
        return pack.render(kernel.present(tenantId, snapshotId, new Principal(principalType, principalId), at));
    }

    private static String single(MultiValueMap<String, String> form, String name) {
        var values = form.get(name);
        if (values == null || values.size() != 1) throw new IllegalArgumentException("Missing or duplicate " + name);
        return values.getFirst();
    }
}
