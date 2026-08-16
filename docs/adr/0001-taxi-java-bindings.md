# ADR 0001: Taxi Java Bindings

Status: accepted

## Context

Applications currently repeat semantic meaning across Taxi and hand-written Java string maps. Build-time generation must improve Java type safety without moving persistence, behaviour, or presentation into Kernel.

## Decision

Taxi is the source of semantic meaning. Human-authored Taxi owns semantic names, fields, roles, relationships, and durable contract versions. Generated Taxi Java Bindings are executable representations and must not be edited. Applications continue to author relational persistence, row mapping, derivations, policies, handlers, Event projectors, authorisation, presentation, and integrations.

Taxi compiler 1.70.0 compiles authored and imported sources. Generation consumes only validated `TaxiDocument`; it does not parse Taxi independently or use raw parser trees. Compiler errors and generator subset failures retain Taxi source, line, and column. Linter findings remain warnings.

Release contains exactly two same-version artefacts:

- Java 25 `saas-kernel` runtime
- Java 21 build-only `saas-kernel-taxi-generator` Maven plugin

Plugin pins Taxi compiler 1.70.0 and Kotlin 2.2.21. Runtime packages one standard Taxi schema, versioned with Kernel, defining `@Subject`, `@Contract`, `@ProjectedState`, `@Fact`, `@Event`, `@ActionService`, and `@Published`. Applications import these annotations. Plugin and runtime versions must match.

Plugin runs in `generate-sources`, writes only below Maven build directory, transactionally replaces its owned output with rollback on publication failure, and registers it as a compile source root. Qualified Taxi namespaces append to one configured Java base package. Invalid Java identifiers, name collisions, and unsupported Taxi constructs fail instead of being transformed. Generated-source compatibility follows Kernel SemVer.

Application persistence remains separate and Application-owned. Generator produces no SQL, schemas, migrations, repositories, queries, or row mappers. Existing deployed persistence and external contracts remain unchanged. Later typed persistence changes must be forward-only, with explicit adapters reading historical durable shapes.

Exact authored Taxi remains the future Orbital publication source. Publication will expose only explicit `@Published` roots and their safe dependency closure. This decision adds no Orbital runtime, endpoint, or direct Action invocation path.

Compatibility is explicit. Historical durable Taxi types remain available. Breaking durable shape changes use a new qualified type and contract version. Typed adapters convert historical Taxi Java Bindings to current bindings without rewriting stored evidence.

## Consequences

Builds gain generated scalar wrappers, records, enums, `Optional`, and immutable lists while existing Kernel semantic APIs remain unchanged in this tracer. Taxi services compile and receive subset validation, but runtime Action descriptors remain later work. A clean consumer build is the compatibility seam. Generated source stays disposable and uncommitted.

Two release artefacts replace issue #32's one-artefact constraint. This is required because source generation belongs in Maven's build lifecycle, not runtime.

## Rejected alternatives

- Java-first schema ownership: duplicates or demotes Taxi meaning.
- Hand-written Java DTOs plus Taxi: permits drift.
- Retaining arbitrary string maps as Application business interfaces: preserves unsafe refactoring.
- Raw ANTLR or another parser: duplicates Taxi compiler semantics.
- Runtime reflection or runtime Taxi compilation: adds hot-path cost and late failures.
- `exec-maven-plugin`, standalone CLI, annotation processor, starter, BOM, or third release artefact: weakens normal Maven and IDE behaviour or adds unnecessary packaging.
- Generated SQL, repositories, migrations, row mappers, policies, handlers, or presentation: crosses Application ownership boundaries.
- Checked-in generated Java: creates stale duplicate source.
- Destructive backfills or silent shape adaptation: violates durable evidence compatibility.
