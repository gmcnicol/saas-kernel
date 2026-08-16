# Security contract

Generated `SemanticBindings` form the closed runtime allowlist. Encoding and decoding require the exact descriptor instance, qualified Taxi name, contract version, canonical format, and SHA-256. Unknown, duplicate, malformed, non-canonical, over-limit, or checksum-mismatched evidence fails before Application behaviour.

Canonical JSON ceilings are 1 MiB, depth 32, 256 fields per object, 10,000 collection elements, and 65,536 characters per string. Applications may tighten but cannot loosen them. Readers and writers are created once during assembly; Taxi compilation and model discovery do not occur in workflow paths.

Cedar authorises discovery and invocation against typed Projected State and Facts. Applicability is not permission. Only an Action Offer created for the principal, Evaluation Snapshot, exact Action descriptor, state checksum, Semantic Pack, and Authorisation Bundle can create an Intent. Published Action descriptions contain no handler reference, credential, endpoint, or alternate invocation route.

`@Published` is fail-closed. Every transitive semantic dependency must also be published, and a selected source file containing an unpublished declaration is rejected. Publication adds no runtime registry, Orbital dependency, HTTP schema endpoint, or authority-bearing service.

PostgreSQL RLS and least-privilege runtime and worker roles protect tenant evidence. Application input limits apply before Candidate Payload construction. See [Operating an Application](operators.md) for role and readiness requirements.
