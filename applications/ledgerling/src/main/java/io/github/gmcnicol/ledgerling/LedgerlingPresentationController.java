package io.github.gmcnicol.ledgerling;

import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.SemanticRegistry;
import io.github.gmcnicol.kernel.application.W3cTraceContext;
import io.github.gmcnicol.kernel.presentationpack.TypedPresentationPack;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingId;
import io.github.gmcnicol.ledgerling.bindings.io.github.gmcnicol.ledgerling.FilingProjection;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
final class LedgerlingPresentationController {
    private final Kernel kernel;
    private final TypedPresentationPack<FilingId, FilingProjection> presentation;
    private final SemanticRegistry registry;
    private final Clock clock;

    LedgerlingPresentationController(
            Kernel kernel,
            TypedPresentationPack<FilingId, FilingProjection> presentation,
            SemanticRegistry registry,
            Clock clock) {
        this.kernel = kernel;
        this.presentation = presentation;
        this.registry = registry;
        this.clock = clock;
    }

    @GetMapping(path = "/presentation/ledgerling", produces = MediaType.TEXT_HTML_VALUE)
    String html(Authentication authentication, @RequestParam UUID snapshotId) {
        Caller caller = caller(authentication);
        return presentation.render(kernel.present(
                caller.tenant(), snapshotId, new Principal(caller.type(), caller.id()),
                Instant.now(clock), FilingProjection.TYPE)).html();
    }

    @PostMapping(path = "/presentation/intents/{offerId}",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<String> invoke(
            @PathVariable UUID offerId,
            Authentication authentication,
            @RequestParam MultiValueMap<String, String> form,
            @RequestHeader(name = "traceparent", required = false) String traceparent,
            @RequestHeader(name = "tracestate", required = false) String tracestate) {
        Caller caller = caller(authentication);
        UUID intentId = UUID.fromString(single(form, "intentId"));
        var payload = registry.decodeForm(
                single(form, "actionType"), single(form, "payloadType"),
                Integer.parseInt(single(form, "payloadVersion")), form,
                Set.of("intentId", "actionType", "payloadType", "payloadVersion"),
                traceparent == null && tracestate == null
                        ? Optional.empty() : Optional.of(new W3cTraceContext(traceparent, tracestate)),
                Optional.empty());
        var intent = kernel.accept(caller.tenant(), new Principal(caller.type(), caller.id()),
                offerId, intentId, payload);
        return ResponseEntity.ok("event: accepted\ndata: " + intent.id() + "\n\n");
    }

    private static Caller caller(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authenticated caller required");
        }
        return new Caller(authority(authentication, "ROLE_TENANT_"),
                authority(authentication, "ROLE_PRINCIPAL_"), authentication.getName());
    }

    private static String authority(Authentication authentication, String prefix) {
        var values = authentication.getAuthorities().stream().map(Object::toString)
                .filter(value -> value.startsWith(prefix)).toList();
        if (values.size() != 1 || values.getFirst().length() == prefix.length()) {
            throw new AccessDeniedException("Exactly one " + prefix + " authority required");
        }
        return values.getFirst().substring(prefix.length());
    }

    private static String single(MultiValueMap<String, String> form, String name) {
        var values = form.get(name);
        if (values == null || values.size() != 1) throw new IllegalArgumentException("Missing or duplicate " + name);
        return values.getFirst();
    }

    private record Caller(String tenant, String type, String id) {}
}
