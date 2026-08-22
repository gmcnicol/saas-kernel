# ADR 0001: Taxi Java Bindings

Status: accepted

## Context

The experimental API repeated semantic meaning across Taxi and hand-written Java string maps. Build-time generation must provide Java type safety without moving persistence, behaviour, or presentation into Kernel.

## Decision

Taxi is the source of semantic meaning. Human-authored Taxi owns semantic names, fields, roles, relationships, and durable contract versions. Generated Taxi Java Bindings are executable representations and must not be edited. Applications continue to author relational persistence, row mapping, derivations, policies, handlers, Event projectors, authorisation, presentation, and integrations.

Taxi compiler 1.70.0 compiles authored and imported sources. Generation consumes only validated `TaxiDocument`; it does not parse Taxi independently or use raw parser trees. Compiler errors and generator subset failures retain Taxi source, line, and column. Linter findings remain warnings.

Release contains exactly two same-version artefacts:

- Java 25 `saas-kernel` runtime
- Java 21 build-only `saas-kernel-taxi-generator` Maven plugin

Plugin pins Taxi compiler 1.70.0 and Kotlin 2.2.21. Runtime packages one standard Taxi schema, versioned with Kernel, defining `@Subject`, `@Contract`, `@ProjectedState`, `@Fact`, `@Event`, `@ActionService`, and `@Published`. Applications import these annotations. Plugin and runtime versions must match.

Plugin runs in `generate-sources`, writes only below Maven build directory, transactionally replaces its owned output with rollback on publication failure, and registers it as a compile source root. Qualified Taxi namespaces append to one configured Java base package. Invalid Java identifiers, name collisions, and unsupported Taxi constructs fail instead of being transformed. Generated-source compatibility follows Kernel SemVer.

Generation also emits one deterministic Semantic Index containing qualified types, roles, Java bindings, field shapes, Action relationships, implementation slots, package dependencies, standard-schema identity, tool versions, generated content, and checksums. Human-authored Semantic Pack manifests retain deployment facts only: Semantic Pack identity and version, Kernel compatibility, Taxi coordinate, Application version, and packaged resources. They do not repeat derivable bindings or generated-content inventories.

Before readiness, Kernel verifies the canonical package checksum, requires exact runtime and generator versions, checks Taxi compiler and standard-schema identity, recompiles packaged Taxi once, compares the Semantic Index with generated descriptors and packaged classes, and requires exactly one Semantic Implementation for every generated slot. This startup check is outside evaluation and execution hot paths.

Application persistence remains separate and Application-owned. Generator produces no SQL, schemas, migrations, repositories, queries, or row mappers.

Exact authored and pinned imported Taxi are the Orbital publication source. `@Published` roots require a fully published semantic dependency closure. The deterministic bundle records exact sources, definition and dependency checksums, and generator/compiler identity. Ordinary Services remain descriptions. Action Services expose no endpoint, handler route, or authority metadata. This decision adds no Orbital runtime or registry.

This pre-release repository is greenfield. The typed contract replaces the experimental map contract outright. No historical decoder, adapter chain, or binary compatibility promise is retained. Candidate Payload roles come only from authored Action inputs. Compatibility and API-baseline tooling begin with the first stable release.

The retained benchmark-only map baseline measures distinct follow-up and Ledgerling-shaped derivation, applicability, Candidate decoding, and Event encoding workloads without restoring map production APIs. On 16 August 2026, Java 25 measurements on the development machine placed the canonical typed workload at roughly 15 to 18 microseconds and 8 to 9 KiB allocated per operation. These are observations, not CI thresholds. Database and Cedar latency remain separate instrumentation concerns. Structural tests fix the reviewed codec call sites, prove codec readers and writers are reused, and reject Taxi parsing or reflective discovery in workflow classes. No pooling, proxy, specialised codec, or cross-transaction cache is justified by this result.

## Consequences

Builds gain generated scalar wrappers, records, enums, `Optional`, immutable lists, typed descriptors, Action relationships, implementation slots, and a checked Semantic Index. A clean consumer build is the generated-source compatibility seam; a packaged Application startup is the assembly compatibility seam. Generated source stays disposable and uncommitted.

Two release artefacts replace issue #32's one-artefact constraint. This is required because source generation belongs in Maven's build lifecycle, not runtime.

## Rejected alternatives

- Java-first schema ownership: duplicates or demotes Taxi meaning.
- Hand-written Java DTOs plus Taxi: permits drift.
- Retaining arbitrary string maps as Application business interfaces: preserves unsafe refactoring.
- Raw ANTLR or another parser: duplicates Taxi compiler semantics.
- Runtime reflection or lazy Taxi compilation in workflow paths: adds hot-path cost and late failures. One eager packaged-Taxi compilation before readiness is required assembly validation.
- `exec-maven-plugin`, standalone CLI, annotation processor, starter, BOM, or third release artefact: weakens normal Maven and IDE behaviour or adds unnecessary packaging.
- Generated SQL, repositories, migrations, row mappers, policies, handlers, or presentation: crosses Application ownership boundaries.
- Checked-in generated Java: creates stale duplicate source.
- Retaining pre-release map compatibility: adds dead paths and weakens the typed contract.
