# Retained JNI qualification

The [issue acceptance reconciliation](cedar-issue-resolution.md) connects this historical qualification to signed release 0.3.1 and the 0.3.2 regression completion. The original source/artifact identities and measurements below are unchanged, not relabelled as new release measurements.

## Decision and scope

The owner chose to retain the prepared scalar Rust/JNI bridge. SDK 4.11.0 remains the patched uncached compatibility/introspection dependency; upstream caching is not a replacement because it drops schema warnings. Actual downstream adoption, including KIT's current 0.3.0 pin, is separate. Kernel packaged-consumer and security checks are not waived.

Qualified implementation: `5d004b595d19c08280b82d120063b18db2e08fe6`, following the platform documentation/probe at `33c6a93`. This is local, unpublished work; private artifacts labelled 0.3.0 are **not the signed released 0.3.0**. No merge, publication or deployment is authorised here.

## Performance: final-artifact sample acceptance

One final run passed all four existing targets against the exact lifecycle-fixed artifacts. The owner confirmed the quiet timing slot through decision `jni-final-timing-slot`. That is coordinated host reservation, not CPU-affinity enforcement or proof of universal production tails. Before/after host snapshots and complete output were retained; no concurrent producer builds/platform probes were run during timing.

Reference equipment was the unchanged pinned Application benchmark at `08a221e30f0177da0607d152ee24be4c7d3653e8`, in a separate detached worktree. It loaded the privately staged Kernel JAR (actual loaded JAR hash verified), not reactor classes. No downstream source, production pin or deployed Application was changed.

Every tuple retained **12 warmups and 60 observations**, the 96 owned/1,000 foreign rows, protected page20, 11 displayed leaves, six context resources, query/order, all policy checks and fresh source/authority/admission/release fences. Counts remained **340 decisions and 14 JDBC executions/page**, **144 decisions and 108 JDBC executions/context**. Native scalar calls and evaluations matched logical decisions. Model/provider network time is excluded; JDBC/native waits are included. Closed-loop client queue time is excluded.

Resource envelope: Linux x86_64/glibc 2.43; Intel i7-1165G7, four physical/eight logical CPUs; Java 25.0.4; benchmark JVM `-Xms512m -Xmx2g -XX:ActiveProcessorCount=4`; PostgreSQL18; **three request connections plus one separately owned guard session**. The fixture verifies all three request slots and the distinct guard backend. The locally cached PostgreSQL image used by the unchanged fixture resolves to `postgres@sha256:a1d02e4bd40c94d3bf2bdd3678c137388e76d9efcd23c285e9429d336a834b44`; the fixture itself still names `postgres:18-alpine`. This does not relabel the historical failed three-total-connection gate as passed.

| Shape | Clients | p50 ms | p95 ms | Max ms | Closed-loop ops/s |
| --- | ---: | ---: | ---: | ---: | ---: |
| page20-directory96 | 1 | 76.236 | 92.870 | 111.446 | 12.581 |
| fenced-context | 1 | 49.937 | 60.882 | 70.852 | 19.346 |
| page20-directory96 | 3 | 105.817 | 117.113 | 126.169 | 27.845 |
| fenced-context | 3 | 63.749 | 69.778 | 76.171 | 46.541 |

Page p95 target: **<200 ms**. Fenced-context p95 target: **<100 ms**. All passed in the final run. Sixty observations establish sample acceptance, not high-confidence p99 or a portable SLO.

Per-operation request-thread allocation was approximately 8.48–8.52 MB/page and 4.80–4.85 MB/context. Owned Rust requested live bytes were 3,402,357 at one client and 3,402,549 at three clients; observed peak was 3,554,671 bytes and conservative retained cache charge 32,201,246 bytes (<64 MiB). Process RSS during the tuples was approximately 1.02–1.03 GiB. Requested Rust heap excludes stacks, allocator/OS retention and SDK-native memory; it is not process RSS. The small caller-count-dependent footprint must not be misreported as retained request/entity data.

Whole Maven-job CPU/RSS observations, including compilation/bootstrap and child JVM work, were:

```text
User time (seconds): 135.33
System time (seconds): 4.76
Elapsed (wall clock) time (h:mm:ss or m:ss): 0:58.90
Maximum resident set size (kbytes): 1186112
```

These are **not per-tuple steady-state CPU measurements**, do not sum simultaneous process RSS, and do not include PostgreSQL's daemon-owned container CPU. No CPU efficiency improvement versus released 0.3.0 is inferred.

An earlier single attempt on source `5724be6` also passed (page p95 103.325/122.710 ms; context 67.830/76.456 ms). It was preserved, not selected over the final result. The final run was necessary because lifecycle cleanup and native test-source changes produced new artifact bytes. Neither run retried until green or discarded observations.

## Lifecycle findings and executed regressions

A real assembly failure was reproduced: native gauge registration threw after preparation and left one live native handle when GC was disabled. The factory now closes an unreturned prepared evaluator on runtime failure/error, retains the original exception and suppresses a cleanup failure. Successful ownership transfer and read decisions are unchanged.

Six new Java tests passed both in normal verification and with Epsilon GC disabled collection:

- Close while admitted encoding is blocked; reopen another evaluator; the old operation cannot return authority.
- Pause a **real native ALLOW** at decoding, close, then resume; Java rejects it at the post-native handle guard. The native evaluator is not mocked. Removing only that guard made the test fail; restoring it passed.
- 64 repeated open/evaluate cycles with multiple close submissions; handles, bundles, retained charges, queues and workers return to zero.
- Warm all four JNI caller threads, then require exact stable requested live bytes through those cycles and exact return after caller-thread shutdown. The first test draft observed 96 bytes when comparing a main-thread baseline with a new JNI closer; calibration fixed the scope, not the tolerance. This is not an RSS equality assertion or a universal native-heap claim.
- Repeated oversized encoding and invalid native preparation restore admission and permit a healthy subsequent operation.
- 32 distinct live bundles reject the 33rd, conserve the remaining owners, admit a replacement after release, and fully drain.

A ninth Rust test injects the accounting charge at the exact cache cap. Same-content handle sharing still works, a distinct preparation is rejected without losing existing owners, and release restores admission. This checks the **accounting condition**, not physical allocation of 64 MiB. It changes test code only; the engine base/patch and ABI remain unchanged, but the rebuilt native binary hash changed and was requalified.

Existing tests retain 128-live-handle exhaustion, nonrecycled tokens, fresh same-UID entities, interrupted/expired admission, saturation recovery, bounded encoding, strict late diagnostics, real Rust Arc lifetime during close, and panic isolation/worker drain.

After full verification has generated native resources and compiled the project, reproduce the no-GC check separately. Epsilon is a **test configuration**, not a production GC recommendation:

```sh
./mvnw --batch-mode --no-transfer-progress -o \
  -Dmaven.repo.local="$PRIVATE_M2" -pl kernel \
  -Dtest=PreparedCedarLifecycleTests \
  -DargLine='-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xmx512m --enable-native-access=ALL-UNNAMED' \
  compiler:compile compiler:testCompile surefire:test
```

Close is not a per-evaluator join while other native work/owners exist. Java rejects a result when it observes a changed handle after native decoding; overlapping calls can still linearize before close if close occurs after that final check. No zero check-to-return race, hard interruption of a Cedar evaluation, deterministic Cleaner/unload deadline, RSS quota or process-OOM isolation is promised. Cleaner and JNI unload remain best-effort backstops; explicit Application/Spring close is required. Poisoned/unrecoverable native state requires process restart rather than permission fallback.

A fresh-context source review found no actionable defects in the cleanup fix/tests. It did not independently execute the builds, mutation or no-GC tests. Concurrent close submissions do not guarantee every possible interleaving; cleanup-failure suppression remains source-reviewed, not fault-injected.

## Final verification and artifacts

Final normal clean verification passed **430 reactor Java tests + 9 native tests**, with zero failures, errors, skipped Java tests or ignored native tests. Module counts: Kernel88, generator16, CRM203, Ledgerling123. The normal isolated packaged consumers passed, and their runtime/generator JAR hashes match the producer pair.

The initial offline full run passed Kernel/native tests but failed fresh consumer bootstrap on unavailable BOMs/plugins. Its logs were preserved. The successful online run changed no source, assertions or dependency versions; it resolved the fresh consumer's dependencies.

```text
source:    5d004b595d19c08280b82d120063b18db2e08fe6
runtime:   242b1fb529dca0caf7908dbcf47ad8a38d21719bf8ab38efd3162a24a9520ec1
generator: 7459545599c7922fbedd214037ea1a4c64f63d3f3455c3ec8f6568e4a32608b8
native:    8b9d83e3cf67321c0afa536da64f0f32c8c28a30e1ccb05c86fe288d9e1efbe1
Cargo.lock:c7918c66cc13b5917f8d468efc121a3b47d85ee18c6dcb8ca2cebefdb5dc59de
patch:     81eabc5b63e0c44a1f0ef937672f05f4cb07c57845a92dcc2dfa050f71592634
```

All source/artifact identities are private qualification evidence, not signed registry receipts. The prior artifact/repository snapshots were not overwritten.

The exact final runtime also passed packaged-JAR functional probes on glibc2.43 and minimum2.36 (auto/prepared/SDK), and glibc2.35 (prepared rejection plus auto/explicit SDK). On unmodified Alpine/musl the owned gate rejected and SDK startup failed. See [platform compatibility](platform-compatibility.md) for JVM/image digests, probe scope, native-loading requirements and unexecuted ARM/macOS/Windows lanes.

## Reproduction and retention

Full producer verification used Java25.0.4 and the pinned native builder:

```sh
KERNEL_NATIVE_CACHE="$PRIVATE_NATIVE_CACHE" ./mvnw --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$PRIVATE_M2" clean verify
```

The unchanged reference fixture uses a private repository containing the exact pair and independently checked manifest:

```sh
./mvnw --batch-mode --no-transfer-progress -o \
  -Dmaven.repo.local="$PRIVATE_M2" \
  -Dkernel.adoption.manifest="$PRIVATE_STAGE/kernel-adoption-final.properties" \
  -Pperformance-read-gates verify
```

Use the coordinated quiet envelope, clean test environment without production/provider secrets, and `verify` (not a smaller goal). Do not build/test concurrently with timing. The manifest must match the actual artifacts; never substitute workspace classes or shared/public 0.3.0 coordinates.

Private evidence under `$HOME/.cache/kernel-jni-validation-20261111`: `final-verification-receipt.json`, `final-performance-receipt.json`, `lifecycle-online-clean-verify.log`, failed offline consumer logs, no-GC and guard-mutation logs, `performance-final{,-retained,-host-before,-host-after}`, separate initial attempt, final artifact snapshot and Maven repository, and `proof-final/` platform logs.

Remaining limits are explicit: no actual ARM/Mac/Windows execution, platform-specific full Application/performance certification, sustained production-load/p99 claim, or new deterministic GC/unload guarantee. These limits do not become passes through skipped tests. Actual downstream product migration is separate work; no production change is included.
