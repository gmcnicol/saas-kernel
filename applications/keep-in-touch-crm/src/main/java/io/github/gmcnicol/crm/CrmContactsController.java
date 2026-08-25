package io.github.gmcnicol.crm;

import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.ContactId;
import io.github.gmcnicol.crm.bindings.io.github.gmcnicol.crm.FollowUpProjection;
import io.github.gmcnicol.kernel.application.Kernel;
import io.github.gmcnicol.kernel.application.TypedProjectedState;
import io.github.gmcnicol.kernel.application.TypedSubject;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class CrmContactsController {

    private final CrmContactQueries contacts;
    private final Kernel kernel;
    private final Clock clock;

    CrmContactsController(CrmContactQueries contacts, Kernel kernel, Clock clock) {
        this.contacts = contacts;
        this.kernel = kernel;
        this.clock = clock;
    }

    @GetMapping("/")
    ResponseEntity<Void> home() {
        return redirect("/contacts");
    }

    @GetMapping(path = "/contacts", produces = "text/html")
    String contacts(Authentication authentication, CsrfToken csrf) {
        var caller = CrmPresentationController.caller(authentication);
        return CrmPresentation.shell(CrmPresentation.contacts(contacts.all(caller.tenantId()), csrf));
    }

    @PostMapping(path = "/contacts", consumes = "application/x-www-form-urlencoded")
    ResponseEntity<Void> create(
            Authentication authentication,
            @RequestParam String displayName,
            @RequestParam String nextContactDueAt) {
        var caller = CrmPresentationController.caller(authentication);
        Instant dueAt;
        try {
            dueAt = LocalDateTime.parse(nextContactDueAt).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Follow-up time must be an ISO local date and time", exception);
        }
        contacts.create(caller.tenantId(), displayName, dueAt);
        return redirect("/contacts");
    }

    @GetMapping("/contacts/{contactId}")
    ResponseEntity<Void> open(
            Authentication authentication,
            @org.springframework.web.bind.annotation.PathVariable String contactId) {
        var caller = CrmPresentationController.caller(authentication);
        var stored = contacts.storedProjection(caller.tenantId(), contactId);
        FollowUpProjection projection = stored.projection();
        var snapshot = kernel.evaluate(new TypedProjectedState<>(caller.tenantId(),
                new TypedSubject<>(ContactId.TYPE, projection.contactId()), stored.version(),
                FollowUpProjection.TYPE, projection), Instant.now(clock));
        return redirect("/presentation/crm/desktop?snapshotId=" + snapshot.id());
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void missingContact() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidContact() {}

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).build();
    }
}
