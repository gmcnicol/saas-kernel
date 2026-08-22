# Migration to the typed Kernel

This repository is pre-release and greenfield. There is no supported map API, legacy durable row decoder, adapter chain, or in-place upgrade contract. Remove pre-release databases and redeploy from current migrations.

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

After the first stable release, compatibility policy and binary API tooling must be introduced against that released baseline. No fictitious baseline or pre-release compatibility layer is retained now.
