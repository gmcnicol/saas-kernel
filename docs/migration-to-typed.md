# Migration to the typed Kernel

This repository is pre-release and greenfield. New typed writes use canonical evidence only; pre-release map APIs are not retained as public contracts. Durable typed contracts evolve by keeping historical Taxi declarations and registering explicit typed compatibility adapters rather than rewriting stored evidence.

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

For a durable shape change: copy the historical Taxi declaration, create the new qualified type and `@Contract` version, regenerate bindings, then register one `TypedCompatibilityAdapter` bean from the generated historical descriptor to the generated current descriptor and one `TypedCompatibilityRequirement` bean for every historical-to-current path that must exist before readiness. Kernel decodes the exact historical descriptor, verifies canonical evidence first, applies one unambiguous adapter chain, and leaves the original bytes unchanged. Declared missing, ambiguous or cyclic adapter paths fail before readiness.

After the first stable release, compatibility policy and binary API tooling are enforced against that released baseline. No fictitious pre-release binary baseline is retained now.
