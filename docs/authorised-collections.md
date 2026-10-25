# Bounded authorised collections (#70)

Extends [AuthorisedReads](authorised-reads.md), using the **same** FORCE-RLS scoped
transaction, generated descriptors, current capability resolution, real Cedar
record/leaf decisions and source/authority fences. No workflow snapshot, payload
archive, SQL compiler, policy-to-SQL translation, dependency or new infrastructure.
Human decision `66-existing-stack-scale-gate` permits bounded single-decision SDK
calls. It supersedes the historical no-batch blockers, not the privacy requirements.

```java
ReadPage<NetworkContactRead> page = reads.collection(request, applicationIndex,
    CollectionOptions.page(40));
ReadPage<NetworkContactRead> next = reads.collection(request, applicationIndex,
    new CollectionOptions(40, 300, cancellation::get, page.continuation()));
```

`ReadPage.records()` contains immutable `AuthorisedRecord<P>` values only.
`exactCount()` is either a genuinely current authorised-index count or unknown;
unknown stays unknown, including on the last page. There are no candidate totals,
skipped counts, candidate identity metadata, raw keys, offsets, source revisions or debug metadata.
A continuation means more **authorised** results, never merely more SQL candidates.
No normal empty intermediate page is produced. Results remain values, not grants
for later disclosure; use the separate [final disclosure fence](fenced-disclosures.md).
Check-to-return/send races remain;
Applications needing strict revocation serialization own it.

## Required Application-owned authorised relation

Implement `AuthorisedIndex<P>` in trusted Application code:

- `queryIdentity()` is a bounded stable template version plus a digest of all bound
  parameters, never private query text. `queryFields()` lists **every** generated
  leaf used by predicates/search/order, **including ordering tie-breakers**. Domain
  containment predicates implementing the authorised relation remain Application
  policy/index correctness, not caller search inputs. Kernel accepts descriptor identity, not
  caller-supplied field strings or query clauses. SQL/templates remain Application
  Java code, not another Kernel language.
- `current(connection, binding)` independently reads current relevant source/index
  revisions and the bundle checksum/authority revision that the relation supports.
  They must match the trusted current request and freshly resolved authority.
  Verify the active Application policy pin, not an old client pin. The assembled
  Cedar bundle is immutable; stop stale assemblies or supply the current pin.
- The relation must contain exactly the current principal/tenant/scope/model/query's
  record-authorised **and query-field-authorised** candidates, in a stable total
  order. Include all domain visibility relations. An index may be maintained SQL,
  an authored SQL relation or Application-owned storage; Kernel supplies none.
- Revisions must be nonblank, bounded (256 characters), nonreused and change on
  relevant withdrawal/value/ordering/permission mutations. They must describe the
  **authorised view**, not a global hidden-row mutation counter. Otherwise token
  invalidations themselves expose hidden activity. Hidden-only mutations must not
  affect relation shape, counts, logical selection work or revisions.
- `select(connection, binding, authorisedOffset, limit, sink)` must stream the next
  `limit` authorised candidates, or the whole remaining authorised suffix when
  shorter. No duplicate/omitted rows, early truncation or raw-candidate scanning
  windows. Kernel supplies an authorised ordinal, not a SQL key; Application owns
  its efficient translation, indexing, SQL work limits and JDBC fetch size.
- An optional exact count must cover that entire current authorised query relation.
  Do not return a candidate count or a cached count from another policy/authority.
  Unknown is preferable. Kernel checks minimum/terminal count consistency but
  cannot prove omissions, SQL correctness or an index's claimed completeness.

Before **any** index callback, each query path needs an error-free scope-level
Cedar Allow for `typed/v1/<ReadPath.qualifiedName()>.query`. Declare the generated
scope Subject as that action's resource type; its UID is tenant/scope-bound,
attributes are empty, and context supplies `readScope` and current capabilities.
This separate permission must cover querying that field across the relation.
A record or parent permit is not query permission. Each returned candidate,
including lookahead, still needs a real Cedar record Allow and real leaf Allow for
**every query path**, even when not selected for output. Ordinary output leaf
Deny still redacts. A denied record/query leaf or foreign scope from an asserted
authorised index is **STALE**, never silently skipped or exposed as a partial page.
Missing actions/schema errors are failures, not permissive fallback.

Callbacks are trusted, **not sandboxed**. Kernel cannot detect a lying index's
omissions, undeclared SQL predicates, stale self-asserted revisions, buffering,
logging, writes or use of another connection. Those violate this contract. The
fixtures exercise real Application SQL and Cedar, intentional stale/denied index
failures, independent PostgreSQL revision changes and count consistency. They do
not prove arbitrary Application SQL/index implementations correct.

## Explicitly closed operations

`collection(request, ReadSource, options)` always throws `UNSUPPORTED`, before
querying. Even complete bounded evaluation can justify an exact mathematical
count while leaking hidden density through variable Cedar work. An opaque cursor
alone does not fix that. No raw-candidate pagination/count fallback is shipped.
Legacy `one`/`many` retain their record-denial/redaction behaviour without new
metadata or a hidden-density timing guarantee.

Arbitrary aggregates/facet output, caller query strings and generic sorting/filter
expressions have no collection API. They are unsupported, not silently evaluated
against confidential fields. The only aggregate here is an optional exact
Application-owned authorised count. Ordinary generated nested/list restrictions
from #69 remain unchanged.

## Continuations and failures

Tokens contain only an encrypted/authenticated expiry, authorised ordinal and
binding digest. AES-GCM uses a process-local random key; no domain payload, grant,
resolved capability values, authority cache or server-side cursor archive exists.
Tokens expire after five minutes. Restart/another instance invalidates them; begin
a fresh request after `STALE`. Tokens are bounded to 102 encoded characters and
ordinals to one billion. Replays are permitted only while the **entire current**
binding still matches: tenant, principal, scope, model/version, selected fields,
query identity/paths, page size, policy, authority, source/index revisions and
count contract. Every resume resolves authority again and rereads index state;
source/index state and authority are fenced again before returning.

`CollectionReadException` has a fixed reason and no original cause/payload:

| Reason | Caller behaviour |
| --- | --- |
| `DENIED` | Invalid configuration/descriptor/authority, missing required capability or scope-wide query permission denied; do not treat as an empty page. |
| `DEPENDENCY` | SQL/resolver/Cedar SDK/schema/evaluation/callback/cleanup failure; retry the whole operation after recovery, never use partial output. |
| `STALE` | Revision/binding/token/authorised-index mismatch or revocation at final fence; discard continuation and begin again under current configuration/authority. |
| `BUDGET` | Candidate/decision/byte/deadline/window overrun (including PostgreSQL SQLSTATE 57014); correct bounds/query and retry whole operation, not a hidden scan continuation. |
| `CANCELLED` | Cooperative cancellation or thread interruption; no automatic resume/partial output. |
| `UNSUPPORTED` | Raw-source metadata or unsupported implementation; use the explicit authorised-index contract. |

A valid empty authorised relation is a successful empty result, distinct from all
failures. A swallowed sink failure stays failed. No failure returns buffered
records, counts or a new token.

## Numerical ceilings and observability

Existing `ReadLimits` cap consumed candidates at 128, selected output leaves at
32, compact domain JSON at 1,048,576 UTF-8 bytes and checkpoint deadline at 5,000 ms.
Query paths are independently capped by the same field limit. `CollectionOptions`
adds a maximum of 4,224 admitted Cedar decisions (including query checks), page
size 1–127 and a cancellation supplier. A page must fit **page size + one**
lookahead inside its candidate budget. Lookahead is checked and byte-charged too;
this is conservative, not silent truncation. Fixed page metadata adds less than
256 compact JSON bytes, independent of domain size. Custom serializers/transports
may differ. Continuation/reference memory is bounded; no domain data accumulates
across requests.

Monotonic checks include connection acquisition time and run before selection,
per decision, after callback/native work and at final return. SQL statement timeout
is restored with caller role/tenant context. Synchronous native SDK calls and
arbitrary callbacks cannot be preempted; this is **not** a hard wall-clock bound.
Application mapping, SQL scans, resolver I/O and JDBC buffering must also be bounded.
No constant-time physical database, cache, GC or network behaviour is claimed.
Authorised-index tests prove hidden-row output shape and logical decision-work
invariance, not a physical timing noninterference theorem.

Operator-only Micrometer meters: `kernel.read.collections` (`mode`, fixed `outcome`),
`kernel.read.collection.{decisions,candidates,bytes,duration}` (`mode`). No payloads,
query text, tenant/principal/resource IDs or private high-cardinality labels.
Decision metrics count admitted call attempts; on successful fixture reads each
maps to one synchronous SDK/JNI call. Authority preparation is request-local and
reused across decisions, never cached across requests.

Measured baseline/comparison and reproduction: [collection measurements](authorised-collection-measurements.md).
