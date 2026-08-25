# Taxi generator reference

The Maven goal runs in `generate-sources`, compiles the Kernel standard schema with all local and imported Taxi, validates the supported subset, atomically replaces its output, registers Java sources, and packages the Semantic Index and publication resources. Runtime and plugin versions must match exactly.

The generator deliberately uses the Taxi compiler as the front-end. Taxi owns parsing, import compilation, language validation, compiler diagnostics, and the `TaxiDocument` seam. The Kernel plugin owns only the Kernel-specific layer over that document: Java bindings, semantic descriptors, implementation slots, the closed transport registry, Semantic Index, publication bundle, and Kernel subset checks. It must not introduce a parallel Taxi parser or a second schema model.

## Java mapping

| Taxi | Java |
| --- | --- |
| named scalar | non-null record wrapper with `@JsonValue` |
| model | record |
| simple symbolic enum | enum |
| nullable field | `Optional<T>` |
| array | immutable `List<T>` |
| `String`, `Boolean`, `Int`, `Long`, `Decimal`, `Double` | boxed Java value |
| `Date`, `Time`, `DateTime`, `Instant` | `java.time` value |

`@Subject`, `@Contract`, `@ProjectedState`, `@Fact`, `@Event`, and `@ActionService` generate typed descriptors and implementation slots. Candidate Payload role comes only from an Action operation input. `@Published` controls schema publication, not runtime authority.

Supported Services have named operations and supported parameter and return types. Ordinary Services are descriptions only. Action Services require exactly one named Candidate Payload and a non-empty Event array containing one Event model or a closed Event union.

Generation rejects invalid Java identifiers, generated-name collisions, record component collisions, inheritance, aliases, general unions, recursion, maps, intersections, streams, constraints, computed expressions, anonymous or partial models, lenient or object-valued enums, unsupported primitives, malformed roles, and invalid Action signatures. Taxi compiler linter findings remain Maven warnings. Errors include source, line, column, and the unsupported construct.

## Pinned imports

Every import needs exact bytes and a lowercase SHA-256. Maven imports must already be exact resolved project dependencies; the generator performs no repository or network lookup.

```xml
<imports>
  <import>
    <coordinate>com.example:shared-vocabulary:1.2.3</coordinate>
    <resource>META-INF/taxi/shared.taxi</resource>
    <checksum>0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef</checksum>
  </import>
  <import>
    <file>src/main/taxi-imports/local.taxi</file>
    <checksum>abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789</checksum>
  </import>
</imports>
```

Imported Taxi compiles in the same `TaxiDocument`; generated Java stays Application-local under `basePackage`. Exact imported source bytes are packaged below `META-INF/saas-kernel/imports`. List those generated import resources in the Semantic Pack manifest's `taxi-sources` when an Application uses imports.

Output is deterministic for identical inputs. The Semantic Index records source, dependency, generated class, tool version, role, relationship, shape, and implementation-slot identity with checksums. A clean rebuild removes stale generated output.
