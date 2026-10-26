---
status: accepted
---

# Prepare immutable Cedar bundles for scalar authorised reads

Decision `kernel-kit-prepared-evaluator-adoption` permits a narrow exception to ADR 0003's native-bridge prohibition: Kernel may own an embedded prepared **scalar** Cedar evaluator for ordinary reads and disclosures. The #74 experiment retained 340 page decisions and measured approximately 101ms page / 68ms fenced-context p95; parsed reuse, worker strategy, encoding and Java preparation all contributed, and small shared-host samples do not establish production tails or native-parsing dominance. Batching remains experimental and is not part of this production path because its exceptional executed-check ordering differs.

The exception permits only immutable schema/policy compilation reuse, bounded native execution and native resources inside the existing same-version runtime artifact. Public read/disclosure interfaces, record/query/field and accessor/encoding order, validation, strict diagnostics, exact decision-local entities, Application SQL/RLS/data/policy ownership and every fresh authority/source/release fence remain unchanged. No permissions, capability/entity/source snapshots, service, permission language, third artifact or publication/deployment authority is introduced.

Use a pinned maintained upstream source/dependency lock, owned codecs/public parsers, integrity-checked loading and explicit lifecycle/admission/failure limits. Pin Cedar `09e21d967f1da2cf2abbe1e0b0b2067b41867735`, recovered from embedded source paths in the installed Java SDK 4.2.3 Linux x86_64 binary; this is source-lineage evidence, not cryptographic build attestation or proof for other platforms. No engine upgrade is combined with this optimisation. Genuinely unsupported prepared-runtime platforms retain the explicitly identified original SDK adapter, without claiming fast-path support; a supported/configured native load or execution failure must not silently fall back. Independent review, full clean verification, privately staged consumer proofs and controlled sequential/concurrent measurements precede integration or readiness claims.
