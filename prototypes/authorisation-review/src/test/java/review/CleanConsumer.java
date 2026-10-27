package review;

import java.util.List;
import review.DisclosureContracts.*;
import review.ScopedRead.*;
import review.bindings.review.Contact;
import review.bindings.review.Filing;

/** Compiles from disposable generated Taxi bindings, never executed as a fake security test. */
final class CleanConsumer {
    static void consume(ScopedRead reads, FinalRelease releases, Scope caller,
                        Query<Contact> contacts, Query<Filing> filings, Sink assistant, Sink export) {
        List<View<Contact>> directory = reads.read(caller, ScopedReadTest.CONTACT, contacts);
        List<View<Filing>> practice = reads.read(caller, ScopedReadTest.FILING, filings);
        Handle<Contact> prompt = releases.prepare(caller, ScopedReadTest.CONTACT, contacts, new Purpose("review.assistant"));
        Handle<Filing> download = releases.prepare(caller, ScopedReadTest.FILING, filings, new Purpose("review.export"));
        releases.release(prompt, caller, new Purpose("review.assistant"), assistant);
        releases.release(download, caller, new Purpose("review.export"), export);
        // reads.read(caller, ScopedReadTest.CONTACT, filings) does not type-check.
        // Leaf.nested(Contact.DETAILS, FilingDetails.REFERENCE) does not type-check.
        // There is no Handle constructor, raw DTO getter, or parent-object output method.
    }
}
