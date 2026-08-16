# Semantic Workflow Kernel

A reusable core for products whose current facts determine which human actions are applicable, authorised, and durably executed.

## Language

**Application**:
The deployable product owning its domain semantics, data, Business Workflow, authorisation, presentation, and external integrations while using Kernel mechanisms.
_Avoid_: Host, pack assembly

**Kernel**:
The product-neutral contracts and mechanisms shared by every semantic workflow product.
_Avoid_: Platform, framework, core kernel

**Semantic Pack**:
An extension defining a product domain through semantic types, facts, actions, policies, and action handling.
_Avoid_: Product pack, domain plugin

**Taxi Java Binding**:
The generated Java representation of a semantic type or operation declared in Taxi. Taxi remains the source of meaning.
_Avoid_: Canonical schema, hand-written DTO, domain model

**Presentation Pack**:
An extension defining how semantic capabilities appear and behave for a particular audience or workflow.
_Avoid_: UI theme, semantic pack

**Authorisation Bundle**:
The versioned Cedar schema and policy set governing principal-specific access for an application.
_Avoid_: Cedar pack, policy pack, semantic pack

**Acceptance Fixture**:
A product-shaped scenario that validates the Kernel contract. Acceptance Fixtures are contrasting peers, not levels in a complexity ladder.
_Avoid_: Demo app, complexity stage

## Workflow

**Projected State**:
The current domain state derived from recorded Events.
_Avoid_: Source of truth, mutable record

**Fact**:
A semantic statement derived from Projected State and used to evaluate business applicability.
_Avoid_: Permission, raw field

**Evaluation Snapshot**:
An immutable result containing Facts and Applicable Actions derived for a subject from one Projected State version, at an explicit evaluation time, using one Semantic Pack version.
_Avoid_: Evidence graph, decision log

**Canonical Evidence**:
The exact versioned UTF-8 JSON and checksum persisted for one typed Projected State, Fact, Candidate Payload, or Event. It protects semantic history and is not an Application business model or query store.
_Avoid_: Source of truth, business JSON, relational projection

**Contract Family**:
The stable qualified lineage identity shared by renamed versions of one durable Taxi role. Each family has one current contract and explicit forward adapters from historical versions.
_Avoid_: Simple type name, Java class hierarchy

**Authorisation Envelope**:
A principal-specific projection containing only Cedar-authorised fields, Facts, and Action Offers for one Evaluation Snapshot.
_Avoid_: Authorised Evaluation, Evaluation Snapshot

**Action**:
A business capability defined by a Semantic Pack.
_Avoid_: Command, button

**Applicable Action**:
An Action whose business preconditions hold for a subject in the current Projected State.
_Avoid_: Authorised action, available button

**Action Offer**:
An Applicable Action that a principal is authorised to discover or invoke.
_Avoid_: Applicable Action, permission

**Intent**:
A durable, accepted request to perform an Action against a known state version.
_Avoid_: Intention, command, job

**Candidate Payload**:
The versioned Action input submitted with an Action Offer for Taxi validation before Intent acceptance.
_Avoid_: Intent, unvalidated Intent

**Stale Intent**:
An Intent whose expected Projected State or Semantic Pack no longer matches current execution context, so it cannot be handled as originally accepted.
_Avoid_: Failed Intent, obsolete command

**Event**:
A recorded domain outcome emitted when an Intent is handled.
_Avoid_: Intent, notification

**Business Workflow**:
The product-specific progression of work as Projected State changes and new Actions become applicable. A Business Workflow may use explicit domain concepts, but is not a universal stage-and-transition graph.
_Avoid_: Intent lifecycle, workflow engine
