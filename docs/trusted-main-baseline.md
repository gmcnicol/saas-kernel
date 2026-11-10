# Trusted Kernel baseline and 0.2.0 release disposition

## Source reconciliation

The production Kernel source is `08a60aa2a35504207df8b34172a88263a62ce1a0`, tree `0984df08b0f0f9c1ef240692a644f945b181e9f8`. It contains the previous remote main, `bc0b7f332a813bb0f7ebb96013c7a91e16a32bca`; no remote changes were discarded. The reconciled source is independently retrievable on `release/trusted-main`.

The earlier local main `a05fb0eb3c2121de1236eaa9b63483e1d4995181` and reviewed integration `10175381e551c2484fde67a60a8cc06f54b179d6` have the same tree, `d5d7b673496ed2c570bb240876276120f0a073e8`. The later intended changes through `08a60aa` retain immutable Cedar preparation and bounded Action-allocation improvements. Experimental evaluator batching is not included. Existing main, integration, experimental refs, worktrees, and unrelated local files were preserved.

The release changes version/publication metadata to `0.2.0`; they do not introduce a Cedar engine upgrade or change the authorisation contracts. The prepared native target remains Linux x86_64/glibc >=2.36. Other targets retain the explicitly identified original SDK route without new platform or performance certification.

## Fresh producer and consumer evidence

Verification on 10 November 2026 used Java 25.0.4, a new private Maven repository, the pinned isolated Rust toolchain, and a separate candidate worktree. No shared Maven artifact was overwritten.

The baseline command was:

```sh
KERNEL_NATIVE_CACHE="$PRIVATE_STAGE/native-cache" \
./mvnw --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$PRIVATE_STAGE/m2" clean verify
```

It passed 407 Java tests, including the isolated Maven adoption consumer and packaged Application integration tests, and 8 owned Rust tests, with no failures, errors, ignored tests, or skipped Java checks.

The freshly built snapshot artifact hashes were:

```text
runtime:   6f9c1987f8a9e1bd5218a3f364e3fbdc31f4d2247edd7fe05756e6ba521347e0
generator: 1bb53c2feff15b51777e8882a956bb5efd8c8b00d56b5c72842428fc7c11ebb2
native:    7875216d4d573f6ddc14334c4e4867e43373c44373be0889df9edc7929fc8bf9
Cargo.lock:c7918c66cc13b5917f8d468efc121a3b47d85ee18c6dcb8ca2cebefdb5dc59de
```

These identify the baseline verification artifacts, not the versioned release JARs. The newly built runtime's complete set of 169 classes and its native library were byte-identical to the current production Kit pin; ZIP/package hashes differed. This is an executable-payload comparison, not reproducible-build attestation.

The exact already-tested runtime/generator JARs and flattened POMs were installed into the private repository without rebuilding them. A fresh detached Kit consumer at `dd765b32ad9d1107a70cbcb831fcbcbb1d8bd8c8` passed its focused read/authority/disclosure/Assistant/module checks and both `PackagedPrivateReadProof` and `PackagedCoreAccessProof`, with the consumer JAR unchanged between packaged proofs. No source-worktree runtime classes, live provider credentials, production data, or public publication were used for these checks.

The independent manifest pins Cedar base `09e21d967f1da2cf2abbe1e0b0b2067b41867735` plus patch SHA-256 `81eabc5b63e0c44a1f0ef937672f05f4cb07c57845a92dcc2dfa050f71592634`. Runtime, generator, actual native bytes, packaged patch, and Cargo.lock identities were checked separately.

## Standards

The independent read-only Standards review of `bc0b7f3...08a60aa` found no actionable documented-standard breaches or release-impacting smell findings. The review did not rerun the build or certify untested platforms.

## Spec

The independent read-only Spec review found no actionable findings against the reconciled #66/#75 contracts, #79 scope, and ADRs 0003/0004. It checked the composed RLS/transaction, current-authority/source, disclosure-lineage, strict-diagnostics, native lifetime/admission/identity, and bounded Cedar patch paths. No experimental batching or cross-request permission/entity cache was found.

Standards: 0 findings. Spec: 0 findings. These reviews do not convert pending advisory investigations into completed security assessments.

## Performance exception: the original gate failed

The unchanged protected-read profile was run once in the agreed idle slot, after other builds and source reviews finished. It retained 60 observations, 12 warmups, the same data/query/order/fields, and all required checks, SQL executions, and authority/release fences.

| Shape | Clients | p95 | Required |
| --- | ---: | ---: | ---: |
| Page20/directory96 | 1 | 90.031ms | <200ms |
| Fenced context | 1 | 68.426ms | <100ms |
| Page20/directory96 | 3 | 167.450ms | <200ms |
| Fenced context | 3 | **137.519ms** | **<100ms** |

The command failed. Its complete report, raw output, provenance, and failed-run artifacts were retained. No failed observation was deleted and no threshold or workload was changed. **The original three-connection performance gate has not passed.**

A separate diagnostic JFR run found aggregate connection-pool waiting in the concurrent context group. A resource probe started and ended with exactly one active connection: the session-level semantic deployment guard permanently holds one of the three pool connections, leaving two for three readers. No connection leak was observed. The guard reservation already existed in the earlier passing source; the full reason for the difference from that sample remains unproven. The earlier host used Linux 7.0.0-42, while this worker used 7.0.0-43; Java 25.0.4 and PostgreSQL 18.6 matched.

A separately approved four-connection diagnostic reduced concurrent-context p95 to 79.219ms and largely removed pool waits. It preserved the data, samples, checks, and fences, but changed capacity. It is not acceptance evidence and has not replaced the three-connection resource baseline. No production pool was enlarged.

After the failed measurement and diagnosis were explained, the human explicitly authorised shipping 0.2.0 because this was a performance limitation rather than an observed functional failure. That decision is a **release-specific performance exception**, not a successful original gate. Functional, security/public-contract, native, artifact-integrity, signing, and clean-consumer checks are not waived.

Further stabilisation is tracked in [Kit #121](https://github.com/hi-kit-co-uk/kitt-saas/issues/121). The original limits remain in force there unless a new resource baseline is explicitly reviewed. Release automation must not label the failed latency result as green.

## Remaining limitations and release evidence

Cedar Java SDK 4.2.3 is retained. The upstream SDK replacement and advisory-specific reachability/regression work in [#76](https://github.com/gmcnicol/saas-kernel/issues/76), [#77](https://github.com/gmcnicol/saas-kernel/issues/77), and [#78](https://github.com/gmcnicol/saas-kernel/issues/78) remain open. No completed advisory assessment, all-platform certification, hard native preemption, or universal production-tail guarantee is claimed.

The signed release workflow publishes the final source/tag, exact versioned artifact hashes, native provenance, independent adoption manifest, and public signing key after its required verification. Those final release receipts supersede the snapshot hashes above for consumption; source or snapshot labels alone are not proof of published availability.

Private baseline logs, manifests, failed measurements, diagnostic JFR, and probe sources are retained under the operator-owned `kernel-release-20261110` evidence directory. Only non-secret source/provenance is committed here. No production Application deployment follows merely from Kernel publication.
