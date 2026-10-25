# Effective capabilities (#68)

Opt in per `TypedAuthorisationModel` with its fourth argument,
`Set<CapabilityType<?>> requiredCapabilities`. **Every** listed capability is
required for that model's field, Fact and Action decisions, at discovery,
acceptance and execution. The two-/three-argument model constructors and the
existing Semantic Bindings constructors retain their legacy behaviour; an empty
requirement set does not invoke a resolver or add Cedar context.

Declare identities in Application Taxi, then regenerate with the matching plugin:

```taxi
import io.github.gmcnicol.kernel.taxi.Capability
import io.github.gmcnicol.kernel.taxi.Contract

namespace example
@Contract(version = 1) @Capability
model Engage { remaining: Int? }
```

This produces `Engage.TYPE`, typed fields and an entry in generated
`SemanticBindings.capabilities()` and the Semantic Index. Capabilities are not
workflow Facts, derivations, Candidates or stored grants. Capability descriptors
must be the identical descriptors in assembled generated bindings. Simple names
used in Cedar must be unique across that assembly; collisions fail startup.

Register one trusted Application `EffectiveCapabilityResolver` bean. For **each**
request it must read the current authoritative grant source and revision, combine
independent valid grants into effective `TypedCapability<Engage>` values, and
return a `Resolution(currentRevision, Context(...))`. Bound context expiry by the
validity of its effective capabilities as well as the Application's freshness
limit. Do not accept browser claims
or reuse authority from a prior operation. Paid/preview/admin provenance belongs
in the Application, never in these Cedar inputs.

Context version 1 must echo exactly the request's tenant, principal, generated
Subject identity (type and canonical external ID) and Kernel `evaluatedAt` time;
it must carry the authoritative current revision and a future `expiresAt`.
Use the resource's canonical scope-specific identity, not a person's ID shared
across unrelated resources. Kernel constructs the request from trusted workflow
state/Offer identity. Snapshot time, caller `authorisedAt`, historical
`executedAt` and async processing time do **not** determine capability freshness.

Kernel rejects missing/ambiguous resolvers, null/malformed results, scope/time/
revision mismatch, expired contexts, duplicate or unassembled descriptors and
missing required capabilities. Resolver failures have fixed denial diagnostics.
The separate `Resolution.currentRevision` is an Application-owned authoritative
read, not another name for a cached context's revision claim. Kernel cannot
sandbox a dishonest Application implementation.

Cedar receives `context.capabilities.<generated simple name>` as a typed record
of that capability's generated fields. Declare this in the Application's existing
Cedar schema, for example:

```cedar
type Authority = { capabilities: { Engage?: { remaining?: Long } } };
// Set context: Authority on the existing action appliesTo declarations.
```

Policies may require `context.capabilities has Engage`, check a typed allowance,
**and** require resource ownership/membership. A capability does not override a
Cedar denial. Opted-in decisions require an **error-free Allow**: Cedar normally
skips policies that fail during evaluation, but Kernel denies a capability-protected
decision if any policy evaluation reports an error, even when another permit matches.
Legacy models retain their existing decision handling. Diagnostics do not expose
policy errors or capability values. Kernel neither reserves nor decrements
allowances: the Application must atomically reserve/consume them in its domain
transaction when required.

## Rechecks, compatibility and cost

Resolution occurs once before discovery can issue any Offers, once during new
Intent acceptance, and again before handling async or exact execution. Exact
execution retains its separate acceptance and execution checks. No authority is
stored in snapshots, Offers or Intents, and no cross-request authority cache is
introduced. Existing bundle, state, payload integrity and applicability checks
remain. On opted-in paths acceptance retries (including exact reconciliation of
committed work) re-read current Application state, derive Facts at the current
Kernel time, resolve current capability values and require an error-free Cedar
Allow for the Action under the current bundle. A still-present capability with
an exhausted allowance, or a current resource restriction, can therefore deny
reconciliation. A missing current resource also denies; Kernel does not reconstruct
historical domain values.

Reconciliation does not rerun business applicability or require the Offer's
pre-success state version/checksum: committed work may have changed both. Policy
is evaluated against the **current**, possibly post-success resource and Facts,
not a fabricated pre-success projection. Denial neither recalls committed effects
nor changes the existing Intent's status. Restored authority can reconcile that
same request without executing it again. Legacy reconciliation is unchanged.

Tests run the shared public workflow contract against the CRM Contact and practice
Filing PostgreSQL/Cedar adapters, including overlapping grants, revocation,
malformed scopes and current-time expiry. Existing workflow and exact-Intent
contracts remain unchanged. The clean generated-consumer build covers old
constructors and rejects mismatched capability values and Candidate roles.

Structural performance review: legacy paths add no resolver I/O. Opted-in paths
add one Application resolution per authorisation phase, not per field/Fact/Action;
they retain the existing discovery/new-execution Cedar decision count and generated
field converters. Opted-in reconciliation adds one current-state read, current Fact
derivation and one Action decision; legacy reconciliation adds none.
No batch performance is claimed. The approved `66-existing-stack-scale-gate`
decision permits bounded per-decision SDK calls; historical no-batch blocker notes
are superseded. Ordinary reads and selected nested scalar output use the additive
[authorised-read contract](authorised-reads.md); bounded collections use a current
[Application-owned authorised index](authorised-collections.md). Final release
fencing remains #71, not #68.
There remains a check-to-use race: Applications needing strict serialization with
revocation/allowance consumption must coordinate their authoritative source and
domain transaction. This contract is not an atomic grant database.
