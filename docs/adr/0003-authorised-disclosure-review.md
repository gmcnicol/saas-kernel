---
status: proposed
---

# Authorised disclosure: scoped reads and current authority (#67)

Review draft for #66, not production implementation approval or issue closure.
Extend existing Java/Taxi/PostgreSQL/Cedar contracts only. ADRs 0001/0002 retain
Application-owned data/SQL/policy, generated bindings and exactly two same-version
release artefacts. No new dependency, language, bridge, service or framework.

## Proposed design

Existing `Kernel.authorise/present` require workflow snapshots; ordinary reads must
not mint snapshots or Action Offers. Extend generated descriptors and filtered
output contracts, rather than invoking workflow evaluation for every candidate.
The isolated [spike](../../prototypes/authorisation-review/README.md) sketches
`read(scope, model, applicationSql)` and a separate final-release seam, test-only.

| Restriction | Owner / proposed mechanism |
| --- | --- |
| Authentication | Application constructs trusted tenant/principal/scope; caller claims are not capabilities. |
| Effective authority | Trusted Application resolver called with Kernel-owned current time for every discovery, acceptance, execution, read and release; failure denies. |
| Candidate containment | Kernel starts/joins tenant transaction, selects least-privilege role; Application owns SQL/mapping and correct FORCE-RLS policies. |
| Resource/field permission | Cedar record gate followed by explicitly selected generated nested leaves; denied records disappear. |
| Output | Kernel constructs immutable typed authorised values, never raw candidate/parent objects or whole structured Facts. |
| Egress | Recheck current authority, policy and source immediately before each bounded irreversible release. |

Authority binds tenant, principal, typed resource scope, evaluation time, expiry and
an authoritative Application revision. Multiple grants can supply one capability;
commercial provenance stays outside Cedar. Application atomically consumes any
allowance in its domain transaction; Kernel owns no grant/quota store.
Canonical resource identity binds tenant, scope type/ID, qualified Taxi type and
record ID, never shared-person provenance. Validate generated descriptors against
assembled bindings. Separate minimal policy inputs from eligible output fields.

Joined transactions must restore **both caller role and prior tenant context** on
success/failure without committing or rolling back caller work; reject incompatible
transactions/datasources. Enforce row/byte/time bounds before unbounded mapping.
Fence authoritative capability/policy/source revisions before output; snapshot time
or equality against a cached self-asserted revision is not current authority.

Parent permission never reveals children. Generated model-bound paths/reconstruction
must support authorised null, indistinguishable hidden/absent fields, per-element
record denial and safe list shape. Fixed errors/redacted diagnostics disclose no
private values. A final-release handle is metadata, not a grant or persisted payload.
There is a check-to-send race; Application may serialize revocation/send for stricter
requirements. Delivered bytes cannot be recalled. No Kernel payload archive.

Preserve existing typed consumers additively. Explicitly configured capability
requirements apply consistently across discovery/acceptance/async/exact execution;
preserve existing state, bundle, payload and applicability rechecks. New paths fail
closed without resolver/model/bundle. Generated descriptors/index changes require
matching runtime/generator versions and clean-consumer/startup validation.

## Evidence, not completed production contracts

Real PostgreSQL 18-alpine, Cedar Java 4.2.3 and generated Taxi candidates prove:
- Wrong tenant/Network SQL is tenant-contained by non-owner FORCE RLS; Cedar denies
  the same person's other Network within the tenant.
- Network member gets only display name despite a parent permit; practice client
  gets only their reference, staff gets both references and working notes.
- Expired/cross-scope authority fails closed. Three tests pass; recorded red tests
  fail for the corresponding missing restrictions. Cross-model compilation fails.

`View<R>.fields()` is deliberately a **string-map sketch**, not final generated
typed output. Candidate/capability bindings are generated; final typed output,
closed generated paths, nullable/list/Fact reconstruction and serializers remain
follow-up work. The adapter owns a fresh connection and rolls back; it does not
prove joined-transaction restoration, revision fencing, pre-materialisation bounds
or current grant revocation. Final release only compiles; no provider/export runtime
coverage is claimed. Trusted Application code is not sandboxed by Java generics.

### Existing measured baseline

Sequential fresh JDBC transactions; 10 warmups, 30 samples, local shared machine,
small synthetic bundle. Includes SQL/setup/mapping; not CI thresholds or true batch.

| Rows × selected leaves | Single JNI calls | p50 / p95 ms |
| --- | ---: | ---: |
| 1 × 1 | 2 | 14.186 / 20.416 |
| 8 × 1 | 16 | 36.446 / 42.067 |
| 32 × 1 | 64 | 110.780 / 127.233 |
| 1 × 3 | 4 | 15.034 / 18.873 |
| 8 × 3 | 32 | 62.538 / 70.085 |
| 32 × 3 | 128 | 204.391 / 225.616 |

Mixed fixture: two candidates, one allowed, three leaves = five single calls.
**#70's no-N+1 requirement is blocked: the existing Cedar binding has no batch
interface.** A loop, shared preparation or concurrency does not satisfy it. No new
infrastructure is proposed. The sketch's 32-row/8-leaf post-mapping checks are not
production budgets; byte/deadline/cancellation limits remain unproven.

Collections are also gated: search/filter/sort only permitted fields; no candidate
totals, skipped counts or raw SQL cursors. Exact authorised counts/facets require
complete evaluation or an Application-owned authorised index. Scope/revision-bound
opaque continuation must invalidate on authority/policy change, but alone cannot
prevent empty-page, exhaustion or timing inference. No safe pagination claim yet.

## Ordered follow-ups (after architecture approval)

1. **#68:** generated capability identity/validation; current resolver tests for
   scope, expiry, overlapping grants, revocation and discovery→accept→execute changes.
2. **#69:** scoped read tests for role **and tenant** restoration/error paths and
   incompatible transactions; generated typed nested/list/null output, record denial,
   minimal policy inputs, source fencing and bounded mapping. Both fixtures.
3. **#70:** remains blocked by existing binding's lack of batch. Retain baseline;
   do not ship a per-row fallback as no-N+1. Safe continuation/count/inference and
   enforceable numerical budgets remain required, not silently waived.
4. **#71:** fresh source/authority checks at release; recording Assistant and separate
   export sinks, purpose/replay/expiry/chunk revocation tests; acknowledge send race.
5. **#72:** reuse reactor/test-only contract harness with real PostgreSQL/Cedar and
   both fixtures; no third published artefact.
6. **#73:** exact-version clean-consumer integration recipe and migration tests;
   document only supported/enforced semantics. KIT #89 consumes #68; #94 consumes
   #69–#71. No product billing or practice-role rules move into Kernel.

Rejected: raw SQL DTO egress, parent/whole-Fact disclosure, caller capability maps,
workflow evaluation per ordinary row, generic ORM/Cedar-to-SQL, persisted payloads,
and loops labelled batching. #67 remains open for required architecture approval;
remaining gaps above are implementation gates, not new public decision questions.
