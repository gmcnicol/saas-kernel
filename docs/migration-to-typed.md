# Migration to the typed Kernel

This repository is pre-release. Typed values use generated descriptors and canonical validation, not a persisted evidence archive. Version 0.2 removes domain payload copies from Kernel; see [the persistence contract](persistence.md) and [the cutover procedure](operators.md#version-02-cutover). Applications must supply `ApplicationWorkflowData` and replace historical Kernel payload reads with domain queries. Pre-release map APIs are not retained as public contracts.

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

After the first stable release, compatibility policy and binary API tooling are enforced against that released baseline. No fictitious pre-release binary baseline is retained now.
