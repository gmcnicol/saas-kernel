---
status: accepted
---

# Authorised disclosure: scoped reads and current authority (#67)

Final architecture record for the contracts implemented by #68–#73, not a tracker
closure or a claim of downstream KIT delivery. The initial scoped-read proposal
and [test-only spike](../../prototypes/authorisation-review/README.md) informed the
review; their string-map output and fresh-connection-only adapter are **historical**,
not adoption APIs. The accepted human decisions establish current resolution,
scoped-query ownership, selected recursive leaves, additive compatibility,
authorised-only collection metadata and fenced release. Decision
`66-existing-stack-scale-gate = bounded-current-api` explicitly supersedes the old
no-N+1 implementation gate. `66-adoption-publication-boundary = staged-artifact-proof`
sets the adoption proof boundary, not public-release authority.

Extend the existing Java/Taxi/PostgreSQL/Cedar contracts only. ADRs 0001/0002 retain
Application-owned meaning/data/SQL/policy and exactly two same-version release
artifacts. No new language, native bridge, service, dependency, framework, generic
ORM, billing/user-management system or payload archive.

## Ownership and authority boundary

| Restriction / path | Application responsibility | Kernel mechanism / output boundary |
| --- | --- | --- |
| Authentication | Authenticate and construct trusted tenant, principal type/ID and canonical scope; no browser authority claims | Validate scope/descriptors/context; no authentication provider or universal roles |
| Effective authority | Resolve current authoritative grants AND nonreused revision on every request; bound expiry, combine independent grants, atomically reserve/consume allowances where needed | Generated capability identities; context validation/current Kernel time; required capabilities at discovery, acceptance/reconciliation, async/exact execution, reads and release |
| Candidate containment | Author SQL/mapping, ENABLE/FORCE RLS and least-privilege grants; maintain correct identities/revisions | Own/join scoped datasource transaction, set runtime role/tenant, bound candidate sink, restore exact prior role/tenant/timeout and preserve caller work |
| Record/field access | Author validated Cedar bundle, minimal policy inputs and required capabilities; supply current policy pin | Error-free record gate before selected generated leaf gates; immutable `AuthorisedRecord`, never raw candidate/parent/structured Fact |
| Workflow | Own Projected State, business applicability, handler/projector and sole durable domain input/results | Evaluation/Authorisation Envelopes, Action Offers, current acceptance/execution rechecks and execution references/receipts, no domain value history |
| List/search/count | Maintain a current principal-scoped authorised relation, declare all query/order paths, completeness and optional authorised count | Scope-level query Allow plus every record/query-leaf check; bounded lookahead, opaque binding-bound continuation; no raw metadata fallback |
| Provider/export/async egress | Own format/provider/sink, durable source-reference jobs, purpose, concurrency, cancellation and retention | `prepare/derive/send` original-lineage fences; only filtered typed values enter formatter; buffer bounded bytes, reread after formatting and before each irreversible sink |

Callbacks, index completeness, RLS/policy correctness and revision maintenance are
trusted Application code, **not sandboxed**. Wrong tenant SQL is contained only by
correct Application RLS; same-tenant record/field rules remain independently Cedar
controlled. A parent/record permit cannot reveal children. No raw DTO may cross a
sanctioned read/presentation/provider/export boundary.

## Canonical identity, freshness and compatibility

- Capability identity is the assembled generated `CapabilityType`, qualified Taxi
  name and contract version, not a caller map or workflow Fact. Context v1 binds
  trusted tenant, principal, generated scope Subject type/canonical ID, exact Kernel
  evaluation time, authoritative revision and future expiry. Resolution rereads the
  current source, not a cached context's self-asserted revision. Commercial
  paid/preview/admin provenance stays outside Cedar; grants may overlap.
- Ordinary resource UIDs unambiguously length-prefix tenant, scope type/ID, root
  Taxi type/version and record Subject type/ID. Shared-human/copy provenance cannot
  bridge Networks/practices. Generated descriptor identity is checked against the
  assembled bindings/index before querying.
- Reads resolve before decisions and again before return, checking equal authority
  revision/effective values and current source revision. Revisions cover relevant
  value/visibility/withdrawal/revoke-restore changes and are never reused.
  Authorised-view/index revisions must not expose hidden-only mutations.
- Policy bundles are immutable per assembly. The trusted Application supplies its
  **current** expected bundle pin and must stop stale assemblies/manage rollout;
  an old client pin is not policy currency. No hot reload/global registry exists.
- Release lineage privately fingerprints every contributing resource identity and
  selected scalar value, current authority and source revision. Re-select and
  reauthorise before formatting, after freezing bytes, before provider/export send
  and again before buffered derived output. Equal JSON from replacement identities
  is insufficient. No stale provider-output rebinding or persisted payloads.
- Existing model/Bindings constructors retain legacy typed workflow behaviour;
  capability requirements are explicitly opted in and apply consistently to all
  phases. New disclosure paths require model/resolver/capability/bundle and deny
  without them. Matching packaged runtime/generator versions and clean startup are
  mandatory; [version labels and migration](../migration-to-typed.md) distinguish
  Maven 0.2.0-SNAPSHOT from `kernel-compatibility=0.1` and API/format v1/v2.

## Safe closed subset and honest bounds

Generated scalar and nested scalar leaves, nullable scalar values and immutable
nested reconstruction are supported. Hidden/absent are externally indistinguishable;
permitted null is distinct. **Parent/list/raw DTO selections, scalar lists,
per-element reconstruction, recursive graphs and whole structured-Fact output are
not supported.** This is closed rejection, not completion of the old proposal's
open-ended nested/list aspirations. A supported projection must be explicitly authored.

Only PostgreSQL **READ COMMITTED** can supply current revision fences. Joined
reads use a savepoint; errors restore prior scope and preserve prior caller work,
not commit/rollback the whole transaction. Incompatible/unbound transactions reject.
Disclosures reject caller transactions/open-session resources and finish owned read
transactions before network I/O. Connection loss prevents guaranteed restoration.

Collections require `AuthorisedIndex`; `collection(ReadSource, ...)` always rejects
`UNSUPPORTED`, even for a complete small scan. Opaque tokens alone cannot hide raw
scan density. Search/order paths (including tie-breakers) need explicit scope query
permission and per-record leaf permission. Counts are exact authorised-index counts
or unknown, never candidate/skipped totals. No arbitrary aggregates/facets, caller
query clauses or raw cursor/paging/count workaround. Continuations expire in five
minutes and are process-local, invalid across restart/instances/current binding changes.

Hard read maxima: 128 consumed candidates, 32 selected leaves, 1 MiB compact JSON,
5,000 ms checkpoint deadline. Collections additionally cap admitted decisions at
4,224 and page size at 127 plus lookahead. Disclosure lifetime is at most five
minutes; formatted and provider-response bytes are independently bounded by the
request limit (at most 1 MiB). No partial result escapes sticky failures. Fixed,
cause-free denial diagnostics and bounded-label operator-only meters disclose no
payload or per-private-record reasons.

Cedar Java 4.2.3 has no true batch API. The approved bounded-current-api revision
permits existing synchronous **per-decision calls**, not loops labelled batching,
a guaranteed N+1 speedup or hard native/callback preemption. The
[measured comparison](../authorised-collection-measurements.md) is slower for indexed
pages because it adds checks; it records actual SDK/SQL work and p50/p95, not a
latency target. Logical hidden-row work invariance is tested, not constant-time
physical SQL/cache/GC/network execution or arbitrary index correctness.

Current checks leave the explicitly accepted check-to-return/send race. Application
may serialize revocation/writers/send for stricter requirements. Delivered bytes
cannot be recalled; a sink may fail after delivery. Handles are bounded process-local
metadata/buffers, not reusable grants, signed downloads or durable jobs. Application
jobs store source/window references and requester/purpose, then reconstruct fresh
bounded disclosures. No open-ended streaming/processing, DLP or provider-correctness claim.

## Implemented follow-ups and tested interfaces, in dependency order

1. **#68:** [`EffectiveCapabilityResolver`](../../kernel/src/main/java/io/github/gmcnicol/kernel/application/EffectiveCapabilityResolver.java),
   generated capabilities and [current workflow resolution](../effective-capabilities.md).
   Both Applications implement `EffectiveCapabilityContract`, including overlap,
   expiry, current-resource/reconciliation and exact/async revocation checks.
2. **#69:** [`AuthorisedReads`](../../kernel/src/main/java/io/github/gmcnicol/kernel/application/AuthorisedReads.java)
   and generated typed leaves. Both `AuthorisedReadTests` cover real PostgreSQL/Cedar,
   field/record denial, scope/RLS, nulls, budgets and transaction restoration/error recovery.
3. **#70:** [`AuthorisedIndex`](../../kernel/src/main/java/io/github/gmcnicol/kernel/application/AuthorisedIndex.java),
   [bounded collection contract](../authorised-collections.md), continuations, query
   permissions/counts and measured single-call work. Unsupported raw metadata is tested.
4. **#71:** [`AuthorisedDisclosures`](../../kernel/src/main/java/io/github/gmcnicol/kernel/application/AuthorisedDisclosures.java),
   [source-bound release](../fenced-disclosures.md); both fixture adapters run recording
   provider/export/user sinks, buffered revocation, expiry/replay/chunks and lineage tests.
5. **#72:** [Test-only reusable adversarial contract](../authorisation-contract-tests.md)
   with both peer Applications; no third published artifact. Generated-consumer negative
   compilation rejects foreign paths, raw DTO/handle promotion and parent/list selections.
6. **#73:** [One adoption path](../getting-started.md) and
   [fresh packaged consumer](../../taxi-generator/src/it/adoption/README.md) using isolated
   Maven staging and real PostgreSQL/Cedar, independent directory/practice examples,
   workflow invocation and final sinks. CI proof is not public publication; actual
   Central release remains separately authorised through [releasing](../releasing.md).

These replace stale “blocked/no APIs approved” implementation notes. Final programme
review/tracker closure remains separate; unsupported shapes are explicitly not delivered.

## Cross-repository map

- [KIT #89](https://github.com/gmcnicol/kitt-saas/issues/89) consumes Kernel **#68**
  for KIT-owned effective grants. KIT still owns payments, preview/admin grants,
  canonical Network scope, current membership and atomic consumption.
- [KIT #94](https://github.com/gmcnicol/kitt-saas/issues/94) consumes Kernel **#69–#71**
  for KIT's own Contact/Network/Assistant/export paths, SQL/RLS, authored policies,
  source/index maintenance and deployment. Normal Application data/policy ownership
  does not move into Kernel. No KIT integration is claimed by this programme.
- KIT #94 still contains its original **without N+1 Cedar queries** benchmark
  acceptance. Kernel's approved bounded-call revision does **not** silently waive
  that downstream acceptance or deliver its integration. Consumer coordination and
  resolution of that wording remain explicit downstream work; no KIT issue/code was
  changed here.

## Rejected alternatives

Raw SQL DTO egress; caller capability maps; provenance/pricing in Cedar; permission
by parent/whole Fact/shared-human identity; workflow snapshots per ordinary row;
generic ORM/Cedar-to-SQL or a second policy/query DSL; hidden predicate/order or
raw-source paging/count; reusable snapshots/signed downloads; stale response rebinding;
payload persistence; new native batching infrastructure or loops mislabelled batching;
third release artifact; speculative authentication/billing/UI frameworks.
