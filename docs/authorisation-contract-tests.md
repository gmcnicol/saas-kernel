# Authorisation acceptance contract (#72)

The existing `acceptance-contract` reactor module is **test-only and not deployed**.
Applications depend on it with test scope. It is not a third release artefact or a
runtime JUnit API. The released runtime and Taxi generator remain the only two
artefacts. No property library, service, native code or new dependency is required.

## Small fixture surface

`AuthorisationContract<P>` extends the existing `AuthorisedDisclosureContract<P>`.
Both Applications' `AuthorisedReadTests` implement this same public contract:

- `ReadFixture<P>` declares the trusted `ReadRequest` (tenant, principal, generated
  scope/root/selected fields, current policy pin and limits), generated resource
  identity and effective `TypedCapability`, Application grants, test clock, literal
  expected JSON and typed `ExpectedField<P,V>` values. Hidden and shown-null are
  distinct expectations; no raw DTO is an authorised result.
- `CollectionFixture<P>` declares the Application's `AuthorisedIndex`, trusted
  request, hidden-only mutation, literal expected pages and denied generated query
  path. It does not define a query language or construct an index for the Application.
- The existing workflow `EffectiveCapabilityContract.Flow` declares Kernel,
  principal/tenant/snapshot, generated projection and typed Candidate Payload. The
  Candidate's generated Action descriptor must be the one in the returned Offer.
- The existing disclosure fixture supplies current source, trusted request and
  source/authority mutations. Recording providers and independent export/user sinks
  observe actual bytes, buffering, replay and freshness, not HTTP status alone.

`ExpectedField` keeps each generated path and its expected value type together;
heterogeneous expectations remain model-bound. `ApplicationGrants` is the existing
synthetic Application grant source, extended only with individual-source revocation
and one independently supplied unrelated capability. It collapses paid/preview
provenance outside Cedar. It is **not** a production grant store or business-condition
DSL. Unique fixture tenants isolate its synthetic grant records. Each Application
still owns its generated meanings, policy, SQL, mapping, FORCE-RLS and domain setup.

## Coverage / public seams

| Contract | Observable assertions |
| --- | --- |
| Workflow | Paid/preview generated Action equivalence; overlap/expiry/revocation; current resource policy; discovery/acceptance/exact/async rechecks; malformed contexts; no repeated/retroactively recalled effects. |
| Ordinary reads | Literal JSON and typed visible/hidden leaves; explicit Cedar forbids overriding permits; independent grants; malformed scope/time/revision/descriptors; wrong filtering with actual PostgreSQL containment. |
| Collections | Exact authorised counts/pages/exhaustion; original continuation survives hidden-only inserts/updates; unchanged admitted Cedar work; changed source/policy invalidates resume; denied search/order paths reject before index callbacks; raw-source metadata stays unsupported. |
| Final release | Safe minimal provider context and independent export; buffered membership/source/current-policy-pin changes; every chunk/replay rechecks; grant expiry/last revocation; provider failure/overflow; no subsequent bytes and no recall claim. |
| Generated consumer | Existing clean build compiles generated capability/read/collection/disclosure use and rejects foreign capability values, foreign paths/pages, parent/list paths, raw DTO/handle promotion and mutable-byte sink signatures. |
| Operators | Public Micrometer counters and captured logs; fixed cause-free exceptions and static categories only, no payloads, raw identities or SDK error text. |

Deterministic JDK/JUnit metamorphic cases use fixed seeds: adding an unrelated
capability with/without the required grant never increases disclosure; repeated
same-person identifiers in other Networks/practices never add output to the trusted
scope. Both current authorised-index adapters insert/update hidden rows, check page
shape/count/decision work and resume the **original** opaque token. Token ciphertext
is deliberately not compared (encryption is random).

`WorkflowTestDatabase` reuses test database configuration. Its containment assertion
runs **inside the public scoped SQL adapter**, before mapping: actual runtime/login
roles are non-superuser, non-bypass and NOINHERIT; the active role is `kernel_runtime`,
not the relation owner; ENABLE/FORCE RLS are set; deliberately foreign SQL sees zero
foreign rows. Unfiltered selection then reaches real Cedar. These checks distinguish
RLS containment from merely relying on later candidate-identity rejection.

Run with Docker and Java 25 first on PATH:

```bash
./mvnw -pl applications/keep-in-touch-crm,applications/ledgerling -am test \
  -Dtest=AuthorisedReadTests,EffectiveCapabilityTests \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw clean verify
```

The full reactor retains the legacy workflow/presentation, Intent and clean-consumer
contracts. There are no mocks of Kernel collaborators; only recording external
provider/sink boundaries and the test clock are substituted.

## Operator diagnostics and limits

`kernel.read.denials` has exactly one static label, `category`, with values
`authority`, `record_policy`, `field_policy`. Initial missing/invalid/unavailable
current authority increments `authority`; ordinary error-free Cedar record Deny
increments `record_policy`; output-leaf Deny increments `field_policy`. These are
aggregate operator hints, **not** a complete audit trace, exact grant diagnosis,
SDK-error taxonomy or per-denied-row external explanation. Later fences retain
existing exceptions/collection outcome metrics; not every failure increments this
counter. Telemetry failures do not grant or deny access. Keep these counters and
existing candidate/decision meters server/operator-only: exporting them to users
could reveal hidden activity. No identity, query text, schema name or payload label
is added. User-facing hidden/absent behaviour and fixed errors are unchanged.

The [read](authorised-reads.md), [collection](authorised-collections.md) and
[release](fenced-disclosures.md) trust/limit contracts still apply. Application
indexes must be genuinely current authorised relations; complete raw scans are not
an inference-safe fallback. These fixture relations and logical-work assertions do
not sandbox/prove arbitrary SQL, prevent dishonest revisions/omissions, guarantee
constant-time PostgreSQL/network execution, hot-reload policies, or atomically
serialize revocation and sending. Policy-change tests supply a new trusted current
pin; they do not pretend to replace the immutable assembled bundle. Fixed synthetic
view revisions are valid only while the fixture's authorised view is unchanged.
Already delivered bytes cannot be recalled. No billing, user administration,
permission DSL, general scenario engine or new adoption framework is introduced.
