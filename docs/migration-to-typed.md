# Migration to the typed Kernel

This repository is pre-1.0. Typed values use generated descriptors and canonical validation, not a persisted evidence archive. Version 0.2 removes domain payload copies from Kernel; see [the persistence contract](persistence.md) and [the cutover procedure](operators.md#version-02-cutover). Applications must supply `ApplicationWorkflowData` and replace historical Kernel payload reads with domain queries. Pre-release map APIs are not retained as public contracts.

Replace old semantic maps as follows:

| Removed shape | Typed replacement |
| --- | --- |
| string Projected State type plus values | `TypedProjectedState<I, P>` and generated `ProjectionType<I, P>` |
| map Fact | generated Fact record and `FactType<F>` |
| map Candidate Payload | generated Candidate record and `ActionType.candidate` |
| map Event | generated Event record and `EventType<E>` |
| string derivation or applicability target | generated derivation slot or `ActionType.bindApplicability` |
| generic handler or projector | `ActionType.bindHandler` or `bindProjector` |
| map authorisation or presentation envelope | typed envelopes and field descriptors |

Delete hand-written semantic DTOs and string field access. Regenerate from Taxi, implement every generated slot exactly once, update Cedar entities to generated qualified identities, then rebuild from clean output. Keep ordinary relational models and query services Application-owned.

For an Application-owned durable shape change: keep the historical Taxi declaration, create the new qualified type and `@Contract` version, and regenerate bindings. Applications may use `TypedCompatibilityAdapter` and `TypedCompatibilityRequirement` for explicitly supported historical values. The codec validates the exact descriptor before conversion; this does not authorise retaining a second copy in Kernel. Missing, ambiguous or cyclic declared adapter paths fail before readiness.

## Current version labels (not interchangeable)

| Contract | Current value / check |
| --- | --- |
| Runtime + generator Maven coordinates | `0.2.0` in this tree; exact same-version pair required; generated Semantic Index records both and startup compares them to the packaged runtime |
| Semantic Pack manifest | `format-version=2` |
| Manifest Kernel compatibility | `kernel-compatibility=0.1`, the exact assembly compatibility marker currently accepted by `ApplicationValidator.SUPPORTED_KERNEL`; **not** the Maven version, a SemVer range or a claim to run a 0.1 binary |
| Semantic Index / Authorisation Bundle manifest | `format-version=1` for each |
| Generated Taxi contracts | Explicit authored `@Contract(version = ...)`; examples use 1, not inferred from Maven/manifest version |
| Typed Cedar action namespace / canonical codec | `typed/v1/` / canonical `formatVersion=1` |
| Effective capability context | `formatVersion=1`, with fresh Kernel time/scope/revision/expiry on every resolution |
| Read/disclosure values and continuations | Additive Java APIs in the matching runtime; handles/tokens have **no durable or cross-process serialization compatibility contract** |

The existing `kernel-compatibility=0.1` has been checked independently of Maven
0.2.0 against the current validator and successful packaged startup. Do
not change it merely to make labels match: no compatibility-marker migration is
part of this change. See ADR 0001 and the [generator reference](taxi-generator.md).

## Adopt current capabilities and disclosures additively

1. Preserve existing typed constructors/models initially. Legacy constructors retain
   their workflow behaviour; adding required capabilities is an explicit permission
   change, not an automatic side effect of upgrading the runtime.
2. Pin the matching runtime/plugin, author generated capabilities and supported
   projection leaves, regenerate from clean output, implement every generated slot
   exactly once and package updated manifests/checksums/index. Compile a clean
   consumer and start the packaged assembly; snippets alone are not compatibility proof.
3. Supply a trusted current resolver and explicitly configure required capabilities
   on each opted-in model. Exercise discovery → acceptance/reconciliation → exact/
   async execution under revocation; old Offers/Intents do not retain a capability
   grant. Recheck current domain resource/policy too. Ordinary read/disclosure paths
   always require capabilities/model/resolver/bundle and have no legacy bypass.
4. Move eligible ordinary egress to `AuthorisedReads` generated scalar/nested leaves.
   Unsupported list/parent/whole-Fact shapes must reject or use a separately authored
   supported projection, not expose raw DTOs. For search/list/count supply a real
   current `AuthorisedIndex`; do not wrap raw `many` with paging/count.
5. Rebuild affected authorised relations on policy/visible data/authority changes;
   atomically advance their nonreused view revisions. Validate current policy pins
   and stop stale assemblies. Drop stale tokens/handles/provider buffers and read
   again; do not rebind old answers, persist payloads or issue reusable downloads.
   Async jobs retain Application references/requester/purpose and reauthorise at execution.
6. Keep the version 0.2 persistence cutover above separate: stop old writers, drain
   unfinished work, migrate Application data before Kernel V6 and do not mix runtime
   versions. A successful additive Java compile is not permission to skip that cutover.

The [adoption command](getting-started.md) proves matching **staged packaged**
artifacts with both independent fixture shapes and public behaviours. It does not
publish to Central. After the first stable release, compatibility policy and binary
API tooling use that released baseline. No fictitious pre-release baseline is retained now.
