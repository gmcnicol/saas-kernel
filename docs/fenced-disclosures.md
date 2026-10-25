# Source-bound final disclosures (#71)

`AuthorisedDisclosures` is an additive Kernel bean over the existing
[scoped typed reads](authorised-reads.md), Cedar model/bundle and current capability
resolver. It is not an Assistant SDK, export engine, durable permission, payload
archive, provider client or replacement for [collection fences](authorised-collections.md).
Existing workflow, Action Offer and asynchronous Intent contracts are unchanged.

## Small supported interface

```java
// Application constructs this from trusted authentication and CURRENT policy/scope.
ReadRequest<NetworkContactRead> current = trustedReadRequest();
ReadSource<NetworkContactRead> source = currentApplicationSource();
var prepared = disclosures.prepare(current, source, "assistant-context", Duration.ofSeconds(30));

AuthorisedDisclosures.Formatter<NetworkContactRead> formatter = (views, output) -> {
    // Only typed, filtered AuthorisedRecord values, never NetworkContactRead DTOs.
    var name = views.getFirst().get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME);
    if (name.visible()) output.write(name.value().getBytes(StandardCharsets.UTF_8));
};
var answer = disclosures.derive(prepared, current, "assistant-context", source, formatter,
    (context, response) -> applicationProviderCall(context.copy(), response));
// Obtain current trusted request/policy again after a long-running provider call.
disclosures.send(answer, trustedReadRequest(), "assistant-context", source, applicationUserSink);

// Separate non-AI path; Application owns CSV/PDF/HTTP/content type/encoding.
var export = disclosures.prepare(current, source, "export", Duration.ofSeconds(30));
disclosures.send(export, trustedReadRequest(), "export", source, formatter, applicationDownloadSink);
```

The example formatter needs an Application-specific empty/null representation;
ordinary missing/denied records can produce an empty list. There is no raw DTO,
Projection, arbitrary payload, parent-object or persisted ReadPage overload.
Existing unsupported read shapes remain unsupported. No convenience constructor
can promote caller bytes to a sanctioned derived result. Fabricated/missing/foreign
handles fail closed; the interfaces are opaque markers, not extension points.
`DisclosureBytes.copy()` returns a defensive byte-array copy. Its diagnostic string
is redacted. Formatters/providers write synchronously to a Kernel-bounded stream;
Kernel freezes a defensive immutable copy before release. Retaining a writer cannot
mutate that frozen value. There is no automatic network retry or fallback.

## Lineage and current fences

Preparation performs a real scoped read, including current resolver, Cedar
record/leaf decisions, least-privilege PostgreSQL/RLS and final source/authority
fences. It retains **no candidates, selected payloads or capability/grant values**.
The opaque immutable handle binds:

- trusted tenant, principal, generated scope, assembled model identity/version;
- exact generated leaf selection, limits and bundle ID/checksum;
- Application-owned purpose, Kernel evaluation time and bounded expiry;
- authoritative scope source revision and effective-authority revision;
- a private SHA-256 fingerprint of current effective capability values and **every**
  contributing authorised resource's typed identity and selected scalar output.

The digest is private integrity metadata, not a public identifier, permission,
authority cache or anonymisation claim. Identity contributes even when its identifier
leaf is denied/unselected. Equal JSON from different resources cannot substitute
for the original source. Hidden and missing leaves remain indistinguishable; neither
resource identities nor revisions/digests escape via the handle's API, default JSON
serialisation or `toString`. Safe views retain the existing hidden/null semantics.

Before formatting, another real read must match the original lineage. Only those
current typed views enter the Application formatter. **After formatting and freezing
all bytes**, Kernel performs another fresh read and checks the original lineage,
current source/authority, request/purpose and expiry. It finishes/restores/rolls back
its owned read transaction before invoking the irreversible provider/export sink.
A version assertion without current source selection and Cedar decisions is never
a final fence. Current-source failure, withdrawal, field denial, changed values,
changed identity, authority or revision discards the entire operation.

`derive` fences the provider send, then buffers its bounded response. That response
retains the **original** lineage and expiry. Later `send(Derived, ...)` rereads and
checks it again; there is no rebind/promote-to-new-sources API. A provider response
buffered while a source is revoked cannot reach a sanctioned user sink. Restoring
access does not bless a different source/revision. A failed provider creates no
derived handle. Replaying any handle always revalidates, including after failures.

Purpose is a bounded opaque Application string, not a Kernel role hierarchy or
lawful-processing policy. Request equality is deliberately strict, including read
limits. Applications must supply their **current authoritative** policy pin, not an
old client pin. Policies are immutable per assembled process; no hot reload or
external policy registry is introduced. Stop stale assemblies as described for reads.

## Bounds, transactions and chunks

- Lifetime must be positive and at most **five minutes**. Expiry is checked before
  and after reads and after formatting; clock movement before evaluation also denies.
- Purpose is nonblank, at most 128 Java characters, with no control characters.
- Retained tenant/principal/scope/policy strings, source/authority revisions and
  contributing resource IDs are capped at 4,096 Java characters each. Generated
  descriptors/paths are assembly-owned; selected leaves are capped by read limits.
- Candidate/field/compact-view-byte/deadline budgets are the existing scoped-read
  limits (128 candidates, 32 leaves, 1 MiB, 5 seconds per read maximum). This uses the
  existing **bounded per-decision Cedar loop**, not batching or a speedup claim.
- Formatter bytes and provider-response bytes are **independently** capped at the
  request output-byte limit, never above **1 MiB**. Overflow is sticky even when a
  callback catches the write error; truncated output cannot escape. Encoding and
  formatting must happen inside the formatter, before the final fence.
- Every disclosure operation rejects caller transactions, synchronisation or bound
  resources (including unrelated open-session scopes). Invoke it outside those
  scopes; there is no implicit transaction suspension. Ordinary reads retain their
  existing join/restore behaviour. Do not
  wrap a provider/download send in a mutation transaction or assume rollback can
  undo delivered bytes. Kernel's own read transaction never spans network I/O.
- There is no unbounded stream API. Each `send` is one complete bounded chunk with
  its own fresh fence. Reuse of a handle is not a grant. Stop future chunks when a
  check fails; already delivered chunks remain delivered. For large exports,
  Application jobs select bounded source windows from their current authorised
  relation and retain their own record references/requester/purpose, not a raw
  Projection or long-lived Kernel handle. #70's current authorised-index/query/
  continuation rules still govern collection navigation; a ReadPage/token cannot
  be converted into disclosure authority or exact-count/raw-scan fallback.

Prepared handles and derived buffers are process-local immutable objects held by
the caller, not a Kernel registry. Application must bound concurrent live objects,
release references promptly, and own cancellation, durable job storage, erasure,
retention, formats, provider integration and lawful processing. No restart/other-
instance serialisation contract is supported; default handle JSON is only a redacted
string. A durable job must reconstruct a fresh bounded disclosure from Application
references under its requester's current authority.

## Honest trust and race boundary

SQL, mapping, RLS, revisions, index maintenance, authentication, purpose, formatters,
providers and sinks are **trusted Application code**, not a Java sandbox. The source
must actually read the authoritative current data/revision on the supplied scoped
connection. Source and authority revisions must be nonreused and cover relevant
mutation, withdrawal and membership changes, including revoke-then-restore with
otherwise identical values. Do not
use hidden-only global mutation counters as externally observable authorised-view
revisions. Kernel cannot prove SQL correctness, discover omitted sources, stop a
callback using another datasource, or prevent an Application formatter injecting
retained private DTO values/old provider answers from outside its typed arguments.
There is no DLP, prompt-injection, LLM-output correctness or lawful-processing claim.
The Application must not perform side effects in a formatter or release provider
responses except through the original derived handle. Transport wrappers must not
defer formatting until after the fence. Keep provider/transport buffering bounded.

Failures are fixed, cause-free `AuthorisationDeniedException`, including dependency,
formatting, budget, malformed-context, provider and sink failures. No private payload
logging, debug identity or payload telemetry is added. Applications must likewise
redact their SQL, callback/provider logs and errors; Kernel cannot sanitise those.
Fatal VM/thread failures are rethrown after cleanup, as for existing reads.

The final fence is immediately before the synchronous sink call, **not atomic with
it**. The explicitly accepted check-to-send race remains. Application may serialize
writers/revocation/send for stricter requirements. Kernel cannot interrupt arbitrary
trusted Java/native/network code, recall delivered bytes, guarantee constant-time
execution, or guarantee restoration after connection loss. Formatter/resolver/SQL
and provider work must return to checkpoints; byte bounds are not hard preemption.

## Evidence

`AuthorisedDisclosureContract`, implemented by both Network and practice
`AuthorisedReadTests`, exercises the public release interface with real PostgreSQL
FORCE-RLS, real Cedar and recording provider/download sinks. It covers minimal
Assistant contexts and separate exports, revocation during formatting/provider
buffering/between chunks, replay, all contributing identities/values (including
identical JSON from replacement identities), independent source revision/withdrawal,
policy/context/purpose/expiry failure, immutable bounded output, swallowed overflow,
provider/formatter/dependency/sink failures, redacted handle serialisation and captured
logs, and no caller transaction across network I/O. The generated clean consumer
compiles typed formatters/provider/export use and rejects raw DTO/handle/byte-array
sink assignments. Existing read/collection/workflow/Action Offer/exact-Intent tests
remain in the reactor. These are fixture guarantees, not proof of arbitrary
Application SQL, formatters, transport or provider behaviour.
