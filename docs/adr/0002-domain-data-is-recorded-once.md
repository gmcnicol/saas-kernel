# Domain data is recorded once

The former evidence design duplicated Application data in Kernel snapshots, Facts, Candidate Payloads and Events, allowing deleted personal data to survive in a second store. The product decision of 27 September 2026 removes those copies: Applications own domain values, while Kernel persists only execution references, versions, integrity checks, authorisation provenance, receipts, status, leases and scheduling metadata. Typed values may pass through Kernel in memory for validation and execution, but are not retained as an evidence archive; adding an erasure subsystem or relocating historical JSON blobs is not an acceptable substitute.

## Consequences

Applications supply current domain state and the sole durable input for pending work through `ApplicationWorkflowData`. Execution consumes that input transactionally: the Application either promotes its domain row to an applied record or removes transient input when writing the result, without retaining an additional payload copy. Historical reads and Undo use domain records, not Kernel Event bodies.

Version 0.2 changes the persistence contract. Existing migrations remain immutable. The upgrade runs Application migrations before Kernel V6 removes old payload columns, and V6 refuses to proceed with unfinished old Intents. Stop old writers and drain old work before upgrading; do not mix runtime versions.
