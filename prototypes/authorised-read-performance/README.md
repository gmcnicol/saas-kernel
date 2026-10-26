# THROWAWAY — Kernel #74 / Kit #98 performance experiment

**Not a production design, release or supported API.** Explicit experimental exception to ADR 0003's prohibition on a native bridge; that ADR is not changed. No policy, grant, selected field, paging or disclosure fence is removed. No shared Maven artifacts are replaced.

## Reproduce

On the approved throwaway worktrees, Java 25.0.4-tem, Docker and authenticated `gh` available:

```sh
bash prototypes/authorised-read-performance/run.sh
```

Default cache/logs: `/tmp/spec85-perf`; override `PERF_ROOT` for a fresh isolated run, `KIT_PERF_WORKTREE` for the approved Kit benchmark worktree. The script dereference-copies the Maven cache (no symlink writes), builds matching runtime/generator privately, runs focused semantic fixtures, measures existing Kit/Kernel paths sequentially, and checks the packaged Kit HTTP/provider/restart proof. Individual stages: `build`, `verify`, `kit`, `kernel`, `proof`. No push/deploy/publication. Each stage has a ten-minute process timebox; this is not native hard preemption. Full unfiltered suites and browsers are deliberately omitted.

```sh
python3 prototypes/authorised-read-performance/summarize.py /tmp/spec85-perf --gate
```

This opt-in signal checks identical logical work and the proposed page20/context sample targets. Eight sequential Kit observations after two warmups; reported p95 is the maximum observation, not a well-estimated production tail. Kernel uses five warmups/twenty observations. Run on a controlled idle machine for any release decision. Java allocated-byte estimates are main-thread-only, not total JVM or native RSS/allocations. `sdk` evaluator timing includes its codec; only `sdk-profile` separates exact pinned SDK serialization/native JNI/decoding. JNI remains opaque: **no assertion that native parsing dominates**. SQL figures are separate synthetic-fixture PostgreSQL logged statement counts, include driver/pool housekeeping, and are not network round trips. Repeated disclosure reauthorisation remains fresh.

## Shape

* `sdk` (default): original BasicAuthorizationEngine scalar path; extra aggregate counters only.
* `sdk-profile`: same SDK private wrapper/codec/JNI, timed separately through throwaway reflective access; compare its result and overhead with the stock path.
* `scalar`: prepared schema/policies + reusable native worker; every logical decision still crosses JNI individually. Java attributes/context are prepared once per immutable candidate.
* `batch`: same parsed bundle and candidate frame, record checked first, then one bounded native query-leaf batch (deny stops), then one selected-field batch (ordinary field deny continues; warning/error stops). Scope-level queries remain scalar. This is a real native loop per JNI batch, **not** Java loops labelled batching. Per-decision entities are parsed separately: no row graph union or same-UID sharing.

`-Dkernel.prototype.evaluator=scalar|batch` and an explicit absolute `-Dkernel.prototype.native=.../libthrowaway_prepared_cedar.so` opt in. A missing/broken adapter fails assembly/read; no silent SDK fallback. Public `AuthorisedReads`/`AuthorisedDisclosures` interfaces remain unchanged. The SDK remains the differential oracle.

## Provenance and maintenance

* SDK: installed `com.cedarpolicy:cedar-java:4.2.3:uber`; hash in `provenance.txt`. Java tag v4.2.3 has **no Cargo.lock**, and its manifest references moving Cedar release/4.2.x. Exact native artifact source commit is unproven; do not claim exact-engine equivalence.
* Experiment: immutable Cedar v4.2.2 commit `4dea3e60201f92139141d65c4a52966e056b007d`, downloaded via `gh`, with `prepared-api.rs` appended to upstream FFI. This deliberately reuses upstream request/entity/schema/policy parsers, validation and detailed diagnostic conversion. The extension and dependency lockfile are committed; the downloaded upstream tree is not. The build does not resolve a moving Cedar branch.
* Official toolchain pinned to `rust@sha256:3914072ca0c3b8aad871db9169a651ccfce30cf58303e5d6f2db16d1d8a7e58f` (Rust 1.90/bookworm). The specific `text_direction_codepoint_in_literal` lint is allowed because upstream BIDI-security-test literals otherwise fail on Rust 1.90. Dependencies use the committed Cargo.lock and `--locked`.
* Linux x86_64/glibc only. Bookworm's glibc floor is below Kit's Noble runtime. No supported multi-OS/native artifact lifecycle, ABI upgrade policy or public artifact is delivered. Split Java/JNI/Cedar source maintenance, SDK-private reflection and Jackson 2 codec exposure are explicit adoption costs.

## Bounds and semantic limits

At most sixteen immutable parsed bundles per process, exact serialized schema/policy content plus the compiled engine identity. At most 1 MiB input/output per call, 64 decisions per native chunk (current query/field frames each at most 32), one reusable 8 MiB-stack worker and one admitted JNI entry. Overload fails closed rather than growing an unbounded queue. No raw native pointers escape; handles never recycle. Panic poisons the worker permanently; malformed input does not invalidate/rebind live handles. No sensitive panic/policy payload logging. Bounds on parsed allocation amplification/RSS are not measured memory quotas.

Native checks the remaining deadline between decisions and after the chunk; Java retains original admission/checkpoints/output/freshness fences. An individual Cedar evaluation is not interruptible; asynchronous cancellation is observed at Java checkpoints, not during that evaluation. Field batching can evaluate later field policies before an earlier allowed leaf's Java accessor/encoding fails, or before cancellation is observed. No partial output escapes, but **that exceptional executed-check ordering is not fully scalar-equivalent**. Treat this as a known reason to revise the batch plan, not production readiness. Policy diagnostic failures and denied records/query gates do stop the native plan.

No permission results, dynamic capability values, actor/source snapshots or entities survive a candidate/read/fence. Differential tracers cover forbids, deny, full normalized diagnostics, warnings, validation, malformed/missing values, same-UID differing graphs, batch gate stop, and malformed/truncated/reordered responses. Existing public PostgreSQL read/disclosure fixtures cover descriptor/tenant/scope checks, RLS/role restoration, budgets, revocation/regrant and release lineage. Finite tests do not prove arbitrary engine equivalence or all native concurrency/lifecycle safety.

Early checkpoint and final report: `/tmp/kitt-spec-85/performance-prototype-{checkpoint,result}.md`. Caller trimming belongs to another implementer; merge only a final reviewed commit/report, and measure it separately after evaluator-only results.
