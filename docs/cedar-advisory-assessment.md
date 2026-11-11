# Cedar advisory assessment and initial SDK comparison

## Disposition and source

Assessment date: 11 November 2026. This records work on #77/#78 and the initial semantic gate of #76, not a release, deployment or bridge-removal decision.

- Released starting tree: `v0.3.0`, `36c0da73e241f21ba1933a9d1cef895a5ff91160`. #79 is closed; the older local-only handoff is historical.
- First security pass: `8ed58c58690a2d037b5cec536cadc4568d40e30e`. **That pass was incomplete**: independent review found implicit-shape coercion and an unintended aggregate workflow ceiling.
- Revised security source: `5724be6473b0f50bb3f7997aa42dc08f3b7dcba9`, branch `investigate/76-cedar-boundaries`. Documentation-only commits may follow it.
- Human decision `cedar-kind-boundary-sdk-upgrade-scope` explicitly authorised including Cedar Java **4.11.0** in the proposed dependency fix while retaining the prepared bridge. It did not authorise merge, publication, deployment or relaxed gates. [ADR 0004](adr/0004-prepared-scalar-cedar-reads.md) records the amendment.
- Prepared engine: unchanged Cedar base `09e21d967f1da2cf2abbe1e0b0b2067b41867735`, crate 4.2.2, plus the existing preparation patch. There is no Rust engine/source/ABI change.
- Upstream 4.11.0 source inspected: `d8dd5c90d883d292d1436f5d12014a6b247f7f07`; its Cargo manifest declares Cedar 4.11.2. Source-tag/version association is not reproducible-build attestation for downloaded binaries.

The private stage is `$HOME/.cache/kernel-cedar-boundaries-20261111`. Maven repositories, native build caches and comparison worktrees are isolated. Private artifacts retain 0.3.0 metadata but **are not the signed released 0.3.0**. They must not overwrite shared or published coordinates.

## Exposure and corrected boundaries

### #78: record/extension confusion through supported converters

The released prepared codec wrote `CedarMap` keys unchanged. The SDK route used the affected 4.2.3 serializer. Both routes promoted a reserved entity-shaped record across the FFI boundary and returned `ALLOW` in an executed negative test. Request validation did not restore the original Java record kind after JSON promotion.

`FieldType.cedarValue` is an Application-supplied conversion boundary used by projection fields, Fact fields and Effective Capability fields. Generated keys in `TaxiJavaGenerator.cedarValue`, Fact names, capability names and enclosing field names come from declarations, not requests. An Application that maps caller-defined metadata to arbitrary Cedar values can nevertheless supply caller-controlled nested keys. No request-header/tag-to-map adapter was found in inspected Kernel/generator production source. The generated Acceptance Fixtures do not establish what every downstream converter does.

The [published advisory][type-advisory] covers reserved `__entity` and `__extn` shapes. Independent review then identified a broader original-kind problem: when the schema expects an entity or extension, schema-guided parsing can also promote bare `{type,id}` / `{fn,arg}` maps or an extension string. Negative tests reproduced implicit entity promotion on both routes. Banning reserved keys alone was therefore insufficient for Kernel's stated record/type boundary.

The revised fix:

1. Upgrades the retained SDK to 4.11.0, using its **public** `Schema.toJsonFormat()` API rather than adding a Cedar-language parser or accessing SDK internals. The SDK's existing Jackson 2 runtime dependency is declared at compile scope because this public method exposes `JsonNode`; the owned encoder continues using Jackson 3.
2. Prepares private, immutable schema metadata in `CedarValueSchema`. Known schema kinds are checked against original Java Value kinds, including nested records/sets, common-type aliases, namespaces, entity references and supported extensions. Full engine schema validation remains enabled and authoritative for complete conformance.
3. Streams kind checks together with `CedarValueCodec` encoding. `CheckedRecord` wraps only the root map and schema metadata; it does not traverse/copy request trees before prepared admission. Existing prepared byte/depth/deadline/worker limits remain in force.
4. Rejects reserved CedarMap keys as defense in depth. Ordinary string values containing reserved names, legitimate typed references/extensions, and **ordinary records with fields named `type`, `id`, `fn` or `arg` remain usable**.
5. Streams SDK/workflow preflight to a discard sink. It does not impose a new aggregate prepared-read byte ceiling on workflow projections plus Facts. Their existing individual canonical admission limits remain unchanged.

This does not claim hard native preemption, a whole-process SDK memory bound, or safety against arbitrary malicious Application code. Decision-local converter results and assembled schema/policy configuration must not be mutated during evaluation. It also does not enable every Cedar 4.11 language/extension feature: authored schemas/policies used by the prepared route must remain compatible with its unchanged pinned engine; unsupported complete Value classes fail closed.

### #78: workflow diagnostics differed from strict reads

`CedarAuthoriser.allows` previously checked policy errors only when the model required Effective Capabilities and did not reject response warnings. Strict ordinary reads already rejected both; completed #75 hardening was reused rather than recreated.

Workflow fields, Facts and Action Offers now require a successful allow with no warnings or policy errors regardless of capability configuration. The public Kernel regression uses a **schema-valid arithmetic-overflow forbid**, a matching permit, and separate clean-allow/matching-forbid controls. Startup schema/policy validation remains enabled.

Restoring only the old diagnostics conditional made the errored-forbid public test fail by releasing a field. Restoring the fix returned the protected outcome. No assertion was relaxed.

### #77: no production policy-source rendering sink found

Production Kernel/generator searches found no `toCedarExpr()` call. `EvaluationAutoConfiguration` and `ApplicationValidator` parse Application-authored classpath policy resources. The prepared route copies existing static policy/template sources and typed template-link UIDs; it does not interpolate request values into policy source. Principal/resource IDs use typed `EntityUID` construction. The owned encoder writes JSON strings, not Cedar source.

Literal regressions exercise ordinary IDs, quotes, backslashes, newlines, Unicode, reserved-name strings and expression-injection payloads. They assert exact decoded literals, equality/non-equality across codec/evaluator boundaries, unchanged policy identity, and that projection/Fact values cannot create a permit or disable a matching forbid. Facts use their existing SDK workflow path; ordinary prepared reads do not acquire a new Fact interface.

`CedarDependencyControlTests` uses `toCedarExpr()` **only as an isolated upstream reproduction**, not a supported Kernel source-building path. On the earlier 4.2.3 comparison it reproduced injected permits, disabled forbids and reserved-map promotion. On 4.11.0 it asserts patched behavior. Its old-source flag was `-Dcedar.control.patched=true` for a patched comparison; revised source defaults that flag to true because the retained SDK is patched. A passing vulnerable-version control means the expected upstream defect reproduced, not that the dependency was safe.

The [injection advisory][injection-advisory] requires a source-rendering sink. None was found in inspected production paths. Application-owned policy builders, external integrations and uninspected downstream code remain assumptions, not universally certified safe paths.

## Regression coverage

| Fixture | Coverage |
| --- | --- |
| `CedarBoundaryTests` | Reserved and implicit entity/extension maps, malformed shapes, direct/record/list nesting, `when`/`unless`, typed controls, supported field converter and workflow errors. |
| `CedarReadBoundaryTests` | Public `AuthorisedReads`, real PostgreSQL, validated policies, converter/capability attacks, both routes, zero protected record/accessor release; ordinary reserved-name strings remain usable. |
| `CedarValueSchemaTests` | Legitimate entity/extension-shaped records; common/root/namespaced aliases; nested typed references; context map/string promotion negatives. |
| `CedarWorkflowSizeTests` | Independently canonical-admitted projection and Fact whose combined attributes exceed 1 MiB still pass an ordinary workflow permit. This is a shared-authoriser regression, not a new large-domain public Application fixture. |
| `CedarLiteralBoundaryTests` | Exact identifier/string preservation; nested data; projection fields/Facts; false permits and matching forbids; unchanged policy identity. |
| `CedarDependencyControlTests` | Explicitly isolated upstream advisory controls, distinguished from Kernel boundary regressions. |
| `CedarWorkflowBoundaryTests` | Public `Kernel.evaluate`/`Kernel.authorise`; no fields, Facts, returned offers or persisted offers after a matching/errored forbid, with a clean ordinary control. |
| Existing contracts | Strict diagnostics, scalar order, native admission/lifetime/integrity, SQL/RLS, authority/freshness/disclosure and isolated packaged runtime/generator consumer fixtures. |

## Verification and historical failures

Platform exercised: Linux x86_64, glibc 2.43, Java 25.0.4, Maven wrapper 3.9.11. `CEDAR_JAVA_FFI_LIB` was unset. The SDK language identifier `getCedarLangVersion()` reports `4.0`; it is not the SDK or Cedar crate version.

```sh
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4-tem"
export PATH="$JAVA_HOME/bin:$PATH"
export STAGE="$HOME/.cache/kernel-cedar-boundaries-20261111"
export KERNEL_NATIVE_CACHE="$STAGE/native-cache"
./mvnw --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$STAGE/m2" clean verify
```

The first source pass completed `final-clean-verify.log` with **417 reactor Java tests and 8 native tests**, zero failures/errors/skips. That green build did not cover the later review-discovered gaps. `review-regressions-red.log` then recorded **three failures**: implicit-map promotion on each route and the aggregate workflow-size regression.

The revised source's focused run (`kind-schema-controls.log`) passed **43 tests**, including those new cases and existing admission/lifecycle regressions. Final revised clean verification is recorded separately in `kind-final-clean-verify.log`; its final totals and package hashes belong in the verification receipt below, not in the earlier green pass.

Earlier failed runs remain in the stage: initial security regressions, a test compilation error, fixture setup failures for an invalid bundle ID and a nonliteral extension constructor rejected by startup validation, and an omitted compile-scope public SDK return type. Fixture failures were not treated as exposure evidence. The final workflow policy uses valid arithmetic, not a validation bypass. `workflow-original-conditional-red.log` preserves the intentionally reverted-code regression proof. The fixed condition was restored before clean verification.

## #76: upstream caching is not an equivalent replacement

Before the dependency amendment, isolated source at the first security pass compared SDK 4.11.0 with and without both upstream caches, using the six-line [comparison patch](cedar-sdk-cache-comparison.patch). No bridge removal or public interface change was made.

| Isolated candidate route | Focused result |
| --- | --- |
| SDK 4.11.0, uncached | 36 tests passed, zero failures/errors/skips. |
| SDK 4.11.0, schema/policies cached | 36 tests ran; **one failure**: warning-only schema returned SDK `ALLOW` versus prepared `INVALID`. All advisory controls passed. |

Logs: `candidate-expanded-uncached.log` and `candidate-expanded.log`. Earlier 29-test runs gave the same differential. Disabling only caching removed the warning mismatch.

The immutable upstream source explains the difference:

- [Java selects stateful authorization when both objects are cached][routing].
- [Schema caching discards parser warnings][schema-cache]; [stateful success returns an empty warning list][stateful].
- [Two native caches default to 1,024 entries each][cache-maps]. These are entry counts, not gross retained-byte charges; parsing precedes separate capacity check/insertion.
- [`Schema.cache()`][schema-java] and [`PolicySet.cache()`][policy-java] use Cleaner reachability, without a public deterministic close/uncache contract.
- [Authorization enters synchronous JNI][sdk-call]; the inspected path does not supply Kernel's queue/worker, deadline/cancellation or post-close-result protocol.

A separate fresh-context research review confirmed these source findings and distinguished inspected historical logs from tests independently reproduced by that reviewer. These are Kernel-contract differences, not a claim that every SDK consumer requires Kernel's stricter warning rule.

**Retain the bridge for now.** The approved SDK upgrade patches the retained dependency and exposes public schema introspection; it does not make upstream caching a viable replacement. Caching stays disabled. Any replacement must preserve warnings, bounded preparation/execution, lifetime, fresh data and every release fence. No performance or complete migration conclusion follows from the focused comparison.

To replay the historical comparison, use a separate worktree at `8ed58c58690a2d037b5cec536cadc4568d40e30e`, then select 4.11.0 explicitly:

```sh
./mvnw --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$STAGE/m2" -Dcedar-java.version=4.11.0 \
  -Dcedar.control.patched=true -pl kernel \
  -Dtest=CedarDependencyControlTests,CedarReadBoundaryTests,CedarBoundaryTests,CedarLiteralBoundaryTests,ReadPolicyEvaluatorTests,PreparedCedarEvaluatorTests \
  test
# Apply the comparison patch from this documentation in that worktree only,
# repeat the command, and preserve the warning-case failure.
```

Those focused candidate runs reused the byte-identified existing prepared native resource and invoked resource/compiler/Surefire goals, not a clean patched-candidate consumer proof. The revised full producer build rebuilds/tests the unchanged owned native source normally. SDK 4.2.3 remains historical isolated comparison evidence, not a selectable production dependency after the approved amendment.

## Platform inventory and remaining gates

The inventory below records this assessment's earlier state. See the subsequent
[platform compatibility qualification](platform-compatibility.md) for executed minimum-libc,
below-floor SDK fallback and Alpine/musl outcomes; that matrix is the current platform status.

Both inspected SDK uber JARs contain Linux x86_64/aarch64, macOS x86_64/aarch64 and Windows x86_64 native entries. ZIP inventory is not execution certification.

| Requested target | Evidence |
| --- | --- |
| Linux x64/glibc | Executed on glibc 2.43. Prepared minimum 2.36 was not independently rerun. SDK Linux ELF symbol requirements reach GLIBC_2.18; this does not prove minimum runtime support or all system dependencies. |
| Linux ARM64/glibc | SDK binary present; ELF references reach GLIBC_2.18. Untested. |
| Apple Silicon | SDK binary present; untested. |
| Intel macOS | SDK binary present; untested. |
| Alpine/musl | No musl-specific SDK native entry found; no execution. Do not infer support from generic Linux entries. |

Consumers still use embedded native code; Cedar does not become pure Java. The existing Kernel producer still uses its pinned Linux-amd64 Docker/Rust builder. No downstream Rust installation requirement was introduced.

Still unrun/unresolved: controlled same-workload protected-page/context performance at one/three clients; CPU/native-memory tails; candidate cache saturation/lifetime/cancellation equivalence; minimum-libc and non-Linux platforms; a fresh actual KIT consumer of these exact modified artifacts; downstream converter/policy-builder audit; explicit new template-link payload matrices and every advisory combination across disclosures. Existing disclosure/freshness contracts do not replace those advisory-specific combinations. Preserve the 0.3.0 three-request-plus-one-guard envelope. No merge, publication or deployment is claimed; #76–#78 remain open until their remaining gates are resolved.

## Verification receipt

Final revised clean verification passed at `2026-11-11T12:27:26Z`: **424 reactor Java tests and 8 owned native tests**, zero failures/errors/skips. Module counts: Kernel 82, generator 16, CRM 203, Ledgerling 123. The normal isolated Maven consumer fixtures passed. The private isolated-consumer repository's runtime and generator JAR hashes match the producer JARs byte-for-byte; this is not an actual KIT adoption proof.

The tested kernel classpath contains exactly one Cedar SDK JAR, `4.11.0:uber`, and the flattened runtime POM resolves that version. The native bridge source/base/patch and native bytes remain unchanged.

```text
runtime 0.3.0:   5885340a211c1c7351334e51dc5ebb37d7a655cc1230be49c6077faed518a3ff
generator 0.3.0: a5f4e469cbf075b910f90fa46b91a3e58d3353a3fb4c15a55cfc739143251ba8
owned native:   7875216d4d573f6ddc14334c4e4867e43373c44373be0889df9edc7929fc8bf9
Cargo.lock:     c7918c66cc13b5917f8d468efc121a3b47d85ee18c6dcb8ca2cebefdb5dc59de
Cedar patch:    81eabc5b63e0c44a1f0ef937672f05f4cb07c57845a92dcc2dfa050f71592634
SDK 4.11.0 JAR: cc841f8ec352dee0330b5137abcdaf76c35be5892e56e7a810db964b1fc08b57
```

The historical SDK 4.2.3 JAR SHA-256 is `8f12d3d98deeef09781c4dd0d628b57f07712e6f2d89a5d400c8b74b5e77dd11`; its native Linux x86_64 entry is `ebaafb3ba13a464f5865c281ea8a523f5ff1d4384ef0134d39dde8543d479b07`. The inspected 4.11.0 Linux x86_64 native entry is `f6557a399739e0ebfba81cc7698ac72910cb3a226d5c9f92fedd1d3e1708238c`.

A fresh-context follow-up security review found no actionable findings and confirmed both earlier findings fixed by source reasoning. The reviewer did not independently run tests, benchmarks or platform checks. Private reports and the machine-readable receipt remain in the stage; this receipt records producer-owned execution, not independent binary attestation.

[type-advisory]: https://github.com/cedar-policy/cedar-java/security/advisories/GHSA-93g4-m6xv-cmvr
[injection-advisory]: https://github.com/cedar-policy/cedar-java/security/advisories/GHSA-qmch-v2q9-wg4p
[routing]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJava/src/main/java/com/cedarpolicy/BasicAuthorizationEngine.java#L72-L99
[schema-cache]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJavaFFI/src/interface.rs#L250-L276
[stateful]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJavaFFI/src/interface.rs#L481-L489
[cache-maps]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJavaFFI/src/interface.rs#L69-L82
[schema-java]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJava/src/main/java/com/cedarpolicy/model/schema/Schema.java#L210-L259
[policy-java]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJava/src/main/java/com/cedarpolicy/model/policy/PolicySet.java#L214-L257
[sdk-call]: https://github.com/cedar-policy/cedar-java/blob/d8dd5c90d883d292d1436f5d12014a6b247f7f07/CedarJava/src/main/java/com/cedarpolicy/BasicAuthorizationEngine.java#L191-L205
