# Ordinary authorised reads (#69)

`AuthorisedReads` is an additive Kernel bean, separate from workflow evaluation.
It does not persist evaluations, mint snapshots/Action Offers, or store domain data.
It uses the existing assembled `ProjectionType`, `TypedAuthorisationModel`,
Authorisation Bundle and [current effective capability resolver](effective-capabilities.md).
The existing `Kernel.authorise/present/accept/execute` contracts are unchanged.

## Application configuration

Use an ordinary generated `@Contract` / `@ProjectedState` binding as the in-memory
candidate shape. No workflow operation is required. The current generator still
allows one Projection contract per Subject; arbitrary unannotated Taxi models are
not read roots. The fixtures use scope-local `NetworkContactId` and `ClientFilingId`.

Configure a `TypedAuthorisationModel<P>` with **minimal policy-input fields**, and
an explicit nonempty required-capability set. These inputs are independent of the
output leaf selection. Ordinary reads supply no derived workflow Facts. Do not
include a whole confidential parent merely to make its children eligible output.
Missing model, generated descriptor, resolver, required authority or matching bundle
denies. Scope Subjects, including standalone Subjects, are generated/inventoried and
validated at assembly. Runtime checks use assembled descriptor identity, not names.

For example, the Network fixture configures:

```java
new TypedAuthorisationModel<>(NetworkContactRead.TYPE,
    Set.of(NetworkContactRead.NETWORK, NetworkContactRead.OWNER),
    Set.of(), Set.of(EngageContact.TYPE));
```

The Application constructs `ReadRequest<P>` from trusted authentication and current
scope/policy configuration, **not client claims**. Supply tenant, principal, generated
scope Subject, root descriptor, selected generated `READ_...` leaves, expected bundle
ID/checksum, and `ReadLimits`. A stale/foreign policy pin does not fall back to another
bundle. Policies are immutable for an assembled process: no hot reload or global
policy registry is added. Applications must stop stale assemblies or supply their
current authoritative policy pin; a pin copied from an old client is not currency.

Cedar actions use the existing `typed/v1/` namespace:

- Record gate: `<qualified projection>.read`.
- Leaf gate: the generated `ReadPath.qualifiedName()` (e.g. `.details.displayName`).
- Context: generated effective `capabilities`, plus `readScope` (the trusted scope's
  external ID). Record attributes contain only configured policy inputs.

Author resource/field rules in the Application's validated bundle. A parent permit
never authorises a leaf. Successful, error-free Cedar record permission is required
before any selected field decision or extraction. Ordinary policy-denied records
disappear; policy-denied fields are not extracted into the output. An unsuccessful
SDK evaluation, SDK warning, policy evaluation error (whether the decision is Allow
or Deny), or evaluation exception instead fails the **entire operation**. It cannot
be treated as a missing record/redacted field or release an earlier buffered result,
even if the source catches the sink's failure. Diagnostics remain fixed and cause-free.
Foreign candidate scope IDs disappear;
wrong tenant, scope descriptor or record Subject descriptor fails the operation.
Resource UIDs are unambiguous length-prefixed tenant + scope type/ID + root type/version
+ record Subject type/ID. Shared-human provenance is not a resource identity.

## SQL/mapping boundary

Implement `ReadSource<P>`:

1. `revision(connection)` reads the authoritative current source revision. It must
   be nonblank, nonreused, change on withdrawal and every relevant source mutation,
   and cover the entire selected scope. Missing individual records should simply
   yield no candidates, not require a missing scope revision.
2. `select(connection, sink)` performs authored SQL and streams **one** mapped
   `ReadCandidate<P>` at a time into the synchronous sink. Map tenant, scope and
   record identities from authoritative rows, not from a shared person's ID or
   unchecked request attributes. Close JDBC resources with try-with-resources.

Use PostgreSQL cursor fetching (`PreparedStatement.setFetchSize(...)` in this
transaction), bound individual SQL values, and do not precollect rows/DTOs. Apply
appropriate SQL work limits/indexes too. Kernel is not an ORM, query DSL, repository,
SQL generator, or Cedar-to-SQL compiler. It cannot prevent a trusted callback from
preallocating a giant object, buffering a JDBC result, logging raw values, retaining
the sink/connection, changing roles, committing, or using another datasource.
Those actions violate the Application contract; this callback is **not sandboxed**.
Do not write through it. Map narrowly and keep SQL/logging/provider boundaries trusted.

Application tables need correct tenant RLS, `ENABLE` **and `FORCE ROW LEVEL SECURITY`,
and explicit least-privilege `kernel_runtime` grants. Runtime login/roles must be
non-owner, non-superuser, non-bypass; Kernel's existing startup role checks still run.
Application RLS correctness is not inferred from SQL or repaired by Kernel.

## Transactions and freshness

Without a caller transaction Kernel owns a read-only JDBC transaction and rolls it
back after reading; it never commits. With a caller transaction it joins the bound
Kernel datasource connection (including Spring JDBC and datasource-participating JPA).
An active transaction without that bound connection, a nontransactional bound
connection, or incompatible isolation is rejected. Only PostgreSQL READ COMMITTED
is supported: repeatable snapshots cannot prove current source revisions.

A savepoint surrounds the read. On success Kernel restores the exact prior role
(including `NONE`), tenant context and statement timeout, then releases the savepoint.
On failure it rolls back **only** that savepoint, restoring scope and recovering from
SQL errors without marking the caller transaction rollback-only or discarding prior
caller work. It never closes/commits/rolls back the caller's whole transaction.
PostgreSQL normalises a previously unset custom tenant GUC to empty after first use;
both represent no tenant and grant no rows. Connection loss prevents any software
from guaranteeing restoration; no result is returned on cleanup failure.

Current #68 authority is resolved before decisions, then resolved again before return.
The authority revision and effective values must remain equal, with each resolution
independently scope/time/revision/expiry validated. Source revision is reread and must
match. Any failure discards the entire result; ordinary diagnostics are fixed
`AuthorisationDeniedException` messages with no original cause, candidate, metadata,
SQL or policy errors. No private payload telemetry is added.

These are check-to-return fences, not an atomic lock against concurrent revocation,
withdrawal or sending. Applications needing stricter consistency own serialization
with their writers. Authorised results are immutable values, **not grants for future
release**. Later provider/export release/revocation work remains #71.

## Typed safe output and supported shapes

```java
Optional<AuthorisedRecord<NetworkContactRead>> one = reads.one(request, source);
List<AuthorisedRecord<NetworkContactRead>> many = reads.many(request, source);
ReadValue<String> name = one.orElseThrow()
    .get(NetworkContactRead.READ_DETAILS_DISPLAY_NAME);
```

`one` returns empty for a denied/missing record; more than one authorised record
rejects rather than choosing arbitrarily. `many` returns an immutable list, with no
candidate totals, skipped counts, identities, source revisions or debug metadata.
Only generated model-bound paths can be selected. The result has no raw candidate,
parent DTO or string-map accessor. Its private JSON projection and `toString` contain
only permitted leaves, reconstructed into immutable nested output. Scalar wrappers,
primitives, enums and nullable scalar leaves are supported; missing nullable parents
produce no child output.

`ReadValue.hidden()` represents both unselected/denied and missing paths. A permitted
nullable leaf is `ReadValue.shown(null)`, serialised as an explicit null. Hidden and
missing fields both disappear from JSON, including when their parent has no remaining
children. There is no raw parent or structured-Fact escape.

**Acceptance gap / closed subset:** collections (including scalar lists), per-element
record filtering/reconstruction and recursive object traversal are not supported.
No parent/list descriptor is generated; fabricated paths reject before querying.
This is intentional closed rejection, not a claim of full nested/list/Fact support.
A different supported scalar/nested projection must be explicitly authored/mapped.

## Enforced limits, not performance promises

`ReadLimits` can lower, but not exceed, these hard maxima:

| Budget | Maximum / behaviour |
| --- | --- |
| Candidates consumed | 128, including denied rows; next sink call aborts before decisions/accumulation |
| Selected scalar leaves | 32, checked before querying |
| Compact JSON output | 1,048,576 UTF-8 bytes, including array brackets/separators even for `one`; counted with a bounded discard writer, not an accumulated encoded payload |
| Deadline | 5,000 ms; monotonic checks at sink/field/final fences; per-statement PostgreSQL timeout restored afterwards |

Single selected strings are also length-checked before serialisation. No partial
result escapes an overrun, even if the source catches a sink failure. These limits
do **not** preempt arbitrary trusted Java code or promise a total wall-clock bound:
a callback/resolver/native call must return to a checkpoint, and SQL timeout applies
to individual statements. Application mapping memory and JDBC buffering must be
bounded as described above. External custom serializers/pretty-printing/transport
encodings can have different byte sizes; the budget is compact Kernel JSON.

Each candidate uses at most one existing single Cedar record call plus one existing
single call per selected leaf of a permitted record. This is a bounded loop, **not
batching** and not an N+1 solution. The approved `bounded-current-api` revision
supersedes historical no-batch blocker notes. `many` itself adds no pagination,
counts, search or continuation guarantees, and does not hide raw-scan timing.
Use the [bounded collection contract](authorised-collections.md) for collection
metadata: it requires a current Application-owned authorised index and rejects
raw `ReadSource` metadata requests. Final release safety remains #71.

## Evidence

`AuthorisedReadTests` in both Applications use real PostgreSQL 18 FORCE-RLS tables,
non-owner runtime roles and real Cedar. Network tests cover wrong-tenant SQL,
same-human/different-Network isolation, independent record/nested-field denial,
current authority and source fences, output safety, budgets and caller transaction
preservation/error recovery. Malformed Cedar requests and real policy arithmetic
errors fail the whole read, including mixed candidate/field buffers and swallowed
sink failures, while ordinary record/field Deny still hides/redacts. Practice tests contrast staff and assigned clients and
prove authorised nulls. Configuration-failure tests isolate missing model/resolver.
The generator's clean-consumer build proves typed leaves/results and compile-time
rejection of cross-model paths, raw DTO assignment and parent/list selections.
The full reactor retains workflow/presentation/Action Offer and exact-Intent tests.
