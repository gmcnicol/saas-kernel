# Cedar issues #76–#78: acceptance and evidence

## Scope and release provenance

This reconciles the three investigations against released **0.3.1**, source `a2157c46ae621cf7e44d438661066d35c96a2345`, and the additional regression coverage prepared for **0.3.2**. It does not reopen the bridge-replacement decision or claim arbitrary Application code is safe.

The [0.3.1 release](https://github.com/gmcnicol/saas-kernel/releases/tag/v0.3.1) and its [successful main pipeline](https://github.com/gmcnicol/saas-kernel/actions/runs/43739763628) establish signing, actual registry-byte/signature verification, and an empty-repository packaged consumer. The signed `release.json` identifies SDK 4.11.0 indirectly through its exact runtime/POM artifacts; the dependency checks and isolated consumer records establish the resolved dependency. A POM property alone is not the proof.

The retained-bridge qualification source remains `5d004b595d19c08280b82d120063b18db2e08fe6`. Comparing its production Java/generator/native source with 0.3.1 finds only the development-classpath version fallback changing from 0.3.0 to 0.3.1. The signed 0.3.1 receipt's native hash equals the qualified binary:

```text
8b9d83e3cf67321c0afa536da64f0f32c8c28a30e1ccb05c86fe288d9e1efbe1
```

This establishes source/native continuity, not byte equality of the differently versioned runtime/generator JARs or a new performance measurement. All historical artifact identities, failed runs and qualification limits remain intact.

## #76: retained JNI qualification

| Acceptance | Evidence and disposition |
| --- | --- |
| Retain the bridge | The prepared scalar bridge remains embedded; SDK 4.11.0 is uncached. The cached-SDK warning-loss comparison remains a failed replacement gate, not a waived warning. |
| Same-workload performance | [Final qualification](jni-qualification.md) records one/three-client page p95 92.870/117.113 ms (<200 ms) and fenced-context p95 60.882/69.778 ms (<100 ms), each with 12 warmups/60 observations. The fixture, all fences and three request connections plus one independent guard session remain unchanged. The earlier failed three-total-session result is preserved. |
| Lifecycle and resource bounds | Six `PreparedCedarLifecycleTests` pass with normal GC and Epsilon/no collection. They cover actual encoding/native-return close seams, failed-assembly cleanup, repeated conservation, distinct-bundle admission and recovery. Nine Rust tests include accounting-cap rejection/recovery, Arc lifetime and panic/worker recovery. Guard-removal mutations demonstrate the protected outcomes are regression-sensitive. |
| Declared Linux support and broader matrix | [Platform qualification](platform-compatibility.md) records actual packaged execution on glibc 2.36/2.43, prepared rejection and SDK routing on 2.35, and unsupported unmodified Alpine/musl. ARM64, macOS and Windows are explicitly unexecuted, not passing lanes. |
| Security and packaged consumers | The 0.3.1 pipeline passes security/public-contract suites, both Application image gates and the registry-only matching runtime/generator consumer. The additional advisory matrices below extend coverage without changing the evaluator or native implementation. |
| Durable provenance and review | The advisory/JNI/platform documents identify source, artifact hashes, commands, independent source-review scope and retained failures. Signed release receipts distinguish public bytes from historical privately staged 0.3.0-labelled artifacts. |

The issue's requested qualification is not a universal latency/RSS promise. Close is not a per-evaluator join, overlapping calls may linearize before close, and Cleaner/unload timing is not deterministic. Explicit close, fail-closed handling and restart after unrecoverable native state remain the operational contract. Actual downstream adoption is explicitly not a prerequisite of #76.

## #77: policy-source injection

| Acceptance | Evidence and disposition |
| --- | --- |
| Trace policy parsing, templates and source rendering | `EvaluationAutoConfiguration` and `ApplicationValidator` parse authored classpath policy resources. No production Kernel/generator `toCedarExpr()` or equivalent request-to-policy-source builder was found. `ReadPolicyEvaluator` passes existing template source and typed `EntityUID` link values, not interpolated policy text. Public assembly does not expose a request-driven template-link builder. |
| Literal payload matrix | `CedarLiteralBoundaryTests` covers quotes, backslashes, newlines, Unicode, reserved-name strings, expression payloads, principal/resource IDs and nested projection/Fact values. Its template-link matrix additionally exercises both typed link slots and hostile template/result policy identifiers through SDK/prepared adapters. |
| Exact literals and policy identity | Equality/non-equality controls distinguish the original literal from another value. Static/template source and link identities remain unchanged. Matching permits cannot bypass a matching linked forbid; false linked permits cannot acquire authority. |
| Ordinary controls and unsupported input | Ordinary strings/IDs remain usable. Unsupported Cedar values fail closed rather than being interpreted as policy source. |
| Dynamic-path coverage | No supported production interpolation sink exists to exercise. `CedarDependencyControlTests` deliberately isolates the upstream `toCedarExpr()` reproduction instead of presenting it as a Kernel path. Typed template linking receives direct adapter coverage. |
| Exposure conclusion | SDK 4.2.3 was affected; the retained SDK is now patched 4.11.0. No production Kernel/generator policy-source sink was found. Application-authored policy builders outside Kernel remain trusted Application code, not a universally audited surface. |

## #78: original value kinds and fail-closed release

| Acceptance | Evidence and disposition |
| --- | --- |
| Map creation and reachability | Generated nested keys and enclosing projection/Fact/capability names come from declarations. `FieldType.cedarValue` is the Application converter boundary for caller-controlled values/keys. No production Kernel/generator request-header, metadata or tag-to-Cedar adapter was found. |
| Reserved shapes, nesting and clauses | `CedarBoundaryTests` exercises valid/malformed `__entity`/`__extn` shapes directly and inside records/sets, through `when` and `unless`, plus implicit entity/extension shapes. Rejection at SDK construction/admission is explicitly distinguished from an actual evaluator result. |
| Preserve kinds without false rejection | `CedarValueSchemaTests` checks aliases/namespaces, nested references, typed entities/extensions and ordinary records named `type`, `id`, `fn`, `arg`. Ordinary string values containing reserved names remain usable. |
| Mismatches, serialization and diagnostics | Existing boundary/evaluator/native tests cover malformed kinds, bounded encoding, native errors and diagnostic precedence. Public workflow controls pair a matching permit with both a matching forbid and a schema-valid arithmetic-overflow forbid. |
| Validation remains enabled | Startup validates authored policies. SDK requests retain their schema and validation flag; the prepared route retains schema/entity validation. Missing/unknown schema/action and unsupported kinds reject rather than selecting permission fallback. |
| No protected release | `CedarWorkflowBoundaryTests` asserts no fields, Facts, returned Action Offers or persisted offers after failure. The expanded `CedarReadBoundaryTests` exercises original-kind promotion and errored forbids through public reads and every `AuthorisedDisclosures` operation, on SDK/prepared routes, using real PostgreSQL. |
| Final release combinations | Converter and Effective Capability entity/extension attacks are applied before preparation, during formatting and after provider response. No new provider/sink bytes are released after the failing fence. A context delivered while still authorised is not retroactively recalled. |
| Exposure conclusion | The original released routes could promote records and the old workflow condition could accept an errored forbid. 0.3.1 fixes those defects with schema-aware original-kind checks and strict diagnostics. 0.3.2 adds missing regression combinations; it does not invent a new runtime fix or waive downstream trust assumptions. |

## Executable regression sensitivity

The new disclosure matrix was tested in an isolated worktree with two intentional, uncommitted reversions, each restored before normal verification:

1. Removing SDK original-kind preflight and prepared checked-record wrappers produced **8 failures/0 errors/0 skips**: malformed kinds could prepare a disclosure instead of raising denial. Restoring the guards passed all eight cases.
2. Removing only the final post-format export recheck produced **8 failures/0 errors/0 skips**: a formatter-time attack could reach the sink. Restoring that recheck returned the protected result.

These are mutation receipts, not newly discovered defects in released 0.3.1. Production evaluator, disclosure and native source remain unchanged apart from release-version metadata.

Focused reproduction after a normal build has generated native resources:

```sh
./mvnw --batch-mode --no-transfer-progress -Dmaven.repo.local="$PRIVATE_M2" -pl kernel \
  -Dtest=CedarReadBoundaryTests,CedarLiteralBoundaryTests \
  compiler:compile compiler:testCompile surefire:test
KERNEL_NATIVE_CACHE="$PRIVATE_NATIVE_CACHE" ./mvnw --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$PRIVATE_M2" clean verify
```

Use Java 25, Docker, an isolated Maven repository/native cache and no production data or provider credentials. Complete resolution logs and mutation reports are retained under `$HOME/.cache/kernel-76-78-resolution.DOI4ridB`; the original advisory and JNI evidence remains in its documented directories. Normal CI and signed publication receipts identify the final committed/released bytes independently of these local runs.

## Explicit boundaries, not hidden unfinished acceptance

- No new native target, all-platform certification, deterministic GC/unload, hard preemption, process-OOM isolation or universal p99/production-load guarantee is added.
- No arbitrary downstream converter, policy builder, SQL callback or external provider is sandboxed or universally audited. Applications retain the documented trust and immutable-input obligations.
- Actual KIT/other Application adoption, production dependency pins and deployments are separate work. The registry consumer is a generic Kernel packaging proof, not evidence of a downstream deployment.
