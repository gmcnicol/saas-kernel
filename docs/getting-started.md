# Adopt the Kernel

Use the [small runnable consumer](../taxi-generator/src/it/adoption/README.md), not
copied KIT implementation code. From the repository root, Java 25 first on `PATH`, Docker running:

```sh
./mvnw --batch-mode -pl taxi-generator -am clean verify -Dinvoker.test=adoption
```

Existing Maven Invoker stages `saas-kernel` and `saas-kernel-taxi-generator` at the
same reactor version (currently **0.2.0**) into
`taxi-generator/target/local-repository`. Forked Maven builds the independent
consumer under `target/it/adoption`, resolving coordinates there, not reactor source
classes. Failsafe starts its **packaged JAR**, asserts Kernel comes from the staged
runtime JAR, and exercises real PostgreSQL 18/Cedar. The normal CI clean verify runs
this too. No credentials, public publication or third released artifact are involved.
For published coordinates and GitHub Maven authentication, see [releasing](releasing.md).
Do not infer registry availability from this staging proof.

## What lives where

| Application owns | Kernel supplies |
| --- | --- |
| Authentication, tenant membership and trusted principal/scope construction | Validated generated descriptor identities and scope/context checks; no authentication provider |
| Taxi meaning, domain Actions, SQL/schema/migrations/mapping and authoritative source revisions | Build-time Taxi Java Bindings/Semantic Index; assembly validation |
| Grants from preview, administration, payments or other sources; current revision/expiry and atomic allowance consumption | `EffectiveCapabilityResolver` contract, validated effective capabilities and current phase rechecks |
| Cedar schema/policies and current policy pin; authorised relation/index maintenance | Real record/leaf/query decisions, filtered immutable typed output and bounded collection navigation |
| Application domain input/results via `ApplicationWorkflowData`, handlers/projectors | Evaluation, Action Offers, Intent scheduling/execution and receipts/references, not domain payload history |
| Formatting, providers, transport, retention/erasure and source-reference jobs | Bounded original-lineage final disclosure fences |

The directory and practice are independent peer examples: their own generated
Subjects, capabilities, SQL relations and Cedar rules, not domain rules built into
Kernel. The larger existing CRM and Ledgerling Applications remain contrasting
Acceptance Fixtures; neither becomes a template superclass for the other.

## Wire one Application

1. Use the sample's parent-independent [POM](../taxi-generator/src/it/adoption/pom.xml):
   one runtime dependency and the **same-version** build-only generator plugin in
   `generate-sources`. Invoker replaces `@project.version@` in the cloned POM; an
   adopter pins one actual available artifact version instead. No starter/BOM.
2. Author Taxi in `src/main/resources/semantic-pack`, regenerate, never edit generated
   Java. Implement each generated applicability/handler/projector/derivation slot
   exactly once. The sample has a directory review Action and no Fact derivation.
3. Register generated bindings/registry, Semantic Pack and Authorisation Bundle,
   explicit `TypedAuthorisationModel`s with minimal policy inputs and required
   capabilities, one trusted current resolver, and one `ApplicationWorkflowData`
   **before migration**. Package the exact Taxi/index/classes and checksummed manifests
   as in the sample POM. Startup validates that assembly, not just Java compilation.
4. Supply Application name/version and a production telemetry subject key/key-id.
   The sample's key and test database password are disposable local fixtures, not
   production credentials. Separate migration credentials (`spring.flyway.*`) from
   the runtime datasource. Runtime login is non-owner, NOSUPERUSER, NOBYPASSRLS,
   NOINHERIT with SET membership in `kernel_runtime`/`kernel_worker`; Application
   tables need ENABLE **and FORCE** RLS, explicit grants and tenant policies.
5. Replace the test's trusted principals with your authenticated adapter. Resolve
   tenant membership, principal **type and ID**, resource scope and current policy
   on the server. Never accept a browser's tenant header, `Staff` claim, capability
   list, policy checksum or shared-person ID as authority. These examples deliberately
   contain no HTTP/authentication framework or user administration.

`CurrentCapabilities` reads authoritative SQL grants and revision on every call.
Its typed `actor` field is the trusted requested principal ID used for ownership
comparison, not a commercial source. Preview/admin/paid rows supply identical
effective capabilities; deleting one independent source leaves another valid source
usable. Writers must atomically update grants and their nonreused revision on
relevant mutation/revocation; expiry also limits context validity. Policy still
independently checks record/field access. No charge or allowance reservation follows
from permission; Application owns those transactions.

## Ordinary reads and Actions

Executable methods are in `AdoptionIT`, with Application adapters in
`DirectoryQueries`, `PracticeQueries` and `DirectoryWorkflowData`:

```java
// All arguments come from trusted Application authentication/configuration.
var request = directoryQueries.request(tenant, principal, directoryId);
var record = reads.one(request, directoryQueries.source(request));
record.ifPresent(view -> consume(view.get(Entry.READ_DETAILS_NAME)));
// privateNote is explicitly selected but has no Cedar permit: hidden, not a DTO/null leak.
```

A read is not workflow evaluation. `one` is empty for denied/missing records and
rejects multiple authorised records. Hidden/absent leaves are indistinguishable;
permitted nullable leaves can explicitly show null. Only generated scalar/nested
leaf paths are supported: **no parent/list/raw DTO or whole structured-Fact
selections**, including scalar lists/per-element reconstruction.

For an Action, load current Application state under its scoped transaction, call
`Kernel.evaluate`, `authorise`, then `accept(tenant, principal, offerId, intentId,
DirectoryActions.REVIEW.candidate(...))`. Process via the normal worker/`processNext`;
use [transaction-mandatory exact execution](exact-intent-execution.md) only when
atomic composition is needed. The sample checks resulting public authorised state
and receipt references. Application stores the review input once, promotes that row
on success and projects the domain update; Kernel does **not** persist Event bodies.
Capabilities are resolved anew for discovery, acceptance and execution, not copied
from the snapshot/Offer. Workflow authority scope is its generated subject; an
ordinary read's scope is explicitly configured (directory/practice here).

## Lists, search and count

`PracticeQueries.search` is one authored SQL template: visible practice files,
reference-prefix search, unique reference ordering. It declares the reference leaf
for **both** predicate and full ordering, binds all SQL values, and supplies a
current per-principal authorised-view revision, policy/authority pins and exact
**authorised** count. Cedar separately checks scope query permission, every record
and query leaf, plus selected output leaves. Staff see notes; assigned clients do not.
Domain assignment containment is Application policy/index logic, not a hidden
caller filter. Application must keep the SQL relation and Cedar rules equivalent.

```java
var page = reads.collection(request, practiceQueries.search(prefix), CollectionOptions.page(8));
var next = reads.collection(request, practiceQueries.search(prefix),
    new CollectionOptions(8, 100, cancellation::get, page.continuation()));
```

Resume only if a continuation is present. Tokens are opaque **process-local**,
five-minute, current-binding continuations: restart/another instance or relevant
revision/policy/authority/query change requires starting again. Exact count may be
unknown and remains unknown at exhaustion. Only optional authorised count is
supported, not arbitrary aggregates/facets. Do **not** implement `many()` + manual
paging/count, raw candidate cursors/counts, undeclared private predicates/order or
hidden tie-breakers. Raw-source `collection` rejects `UNSUPPORTED`; a trustworthy
current authorised relation is required. Kernel cannot prove SQL omissions/index
correctness or provide constant-time physical database execution.

## Assistant, export and async source references

The sample formats only permitted names into a recording provider's context and
keeps its response in the original `Derived` handle. `prepare → derive → send`
performs fresh source/authority/Cedar checks before provider release **and** before
buffered user output. No old provider answer may be rebound to newly authorised
sources; after revoke/restore discard it and recompute from a fresh preparation.
There is no prompt-injection/DLP/provider-correctness guarantee.

Non-AI export uses `prepare → send(prepared, currentRequest, purpose, source,
formatter, sink)`. Format completely inside the bounded formatter; do not defer
encoding until after the fence. No caller transaction/open-session resources may
span a disclosure call. Each bounded chunk needs its own fresh checks; stop on failure.

For async exports, persist Application-owned source/window references, requester,
tenant and purpose, **not** DTOs, provider payloads, Kernel handles or reusable signed
downloads. At execution reconstruct current trusted request/source, prepare afresh
and send one bounded chunk. Resolve current membership/principal classification too:
a saved `Staff` assertion is not current staff authority. The resolver's authoritative
grant source must reflect membership withdrawal as well as entitlement changes. The sample's `ExportJob` demonstrates that reconstruction,
not a new durable job service. Application owns job storage, scheduling, cancellation,
concurrency and erasure; collection navigation still requires the authorised index.

## Policy changes, failures and actual limits

Update Application policy/Taxi, regenerate when needed, rebuild checksums/indexes,
validate and start the matching assembly. Rebuild affected authorised relations and
atomically advance relevant authorised-view/source/authority revisions. Hidden-only
changes must not advance another principal's view revision. Publish the current
trusted policy pin/stop stale assemblies before serving new requests; there is no
hot reload/global registry. Discard stale continuations/preparations/derived output
and reauthorise from current sources. The sample tests visible-view revision and
index policy mismatch rejection; [assembly tests](../applications/keep-in-touch-crm/src/test/java/io/github/gmcnicol/crm/ApplicationAssemblyIT.java)
cover incompatible/tampered packaging.

- Denied records/fields hide; malformed authority, dependency/Cedar errors and
  budget failures never become success/empty or partial output. Reads/disclosures
  throw fixed cause-free `AuthorisationDeniedException`; collections classify
  `DENIED`, `DEPENDENCY`, `STALE`, `BUDGET`, `CANCELLED`, `UNSUPPORTED`. Do not expose
  operator meters/SQL/provider errors or private per-record denial reasons.
- Wrong SQL is still RLS-contained in the negative directory example **because the
  Application authored correct FORCE-RLS policies**. Cedar filters same-tenant
  ownership/scope separately. Missing capability denies; record permission never
  implies private-field permission. Trusted callbacks are not sandboxed.
- Reads join/restore the bound Kernel datasource transaction or roll back their own
  read-only transaction; **READ COMMITTED only**. Prior role, tenant and timeout are restored;
  caller work is preserved. Lost connections cannot guarantee restoration.
- Maximum 128 candidates, 32 leaves, 1 MiB compact output, 5-second checkpoint deadline;
  collections add at most 4,224 decisions, page size 127 plus lookahead. The sample
  tightens these. These are bounded synchronous Cedar calls, **not batching**, hard
  native/callback preemption, constant-time SQL or a guaranteed speedup. See the
  [actual measurements](authorised-collection-measurements.md), not latency promises.
- Current-source/current-authority checks leave the accepted **check-to-send race**.
  Application may serialize revocation/writers/send for stricter needs. No recall of
  delivered bytes; sink failure can occur after delivery. No payload persistence or
  future-release grant is conferred by an authorised value/handle.

[Version/migration contract](migration-to-typed.md) and the linked read/collection/
[disclosure reference](fenced-disclosures.md) define the complete supported subset.
