# Persistence boundary

`generic execution, specific data` is a Kernel invariant.

Applications own their canonical business data in ordinary domain-specific relational tables,
constraints, indexes, migrations, read models, and query services. Taxi defines what projected
values mean. It does not prescribe tables, columns, joins, materialised views, or storage engines.
An Application may supply Kernel Projected State from any normal Application-owned query shape.

Kernel persistence contains execution metadata only: evaluation provenance and state identity,
Action Offers, Intent status, Execution Receipts, audit, leases and reevaluation schedules.
It does not store Projected State values, Facts, Candidate Payload bodies or Event bodies.
Canonical encoding validates typed values in memory and computes integrity checks; encoded
bytes are not an evidence archive. Kernel cannot reconstruct deleted Application records.

One `ApplicationWorkflowData` binding supplies current domain state and pending input in the
same transaction and tenant context. It must not switch database roles or use another datasource.
Pending input must exist once in domain-owned storage. Prefer promoting that same domain row
when execution succeeds, as the CRM interaction fixture does. When updating an existing record,
remove the unapplied input in the same transaction that commits the result. Failed and stale
Intents discard unapplied input; retryable work retains it. Never create a parallel Event store,
snapshot archive or generic historical payload table in the Application to replace the removed
Kernel copies.

`Kernel.execute(...)` can compose one exact Intent with other Application writes only when the
caller transaction uses this same datasource and Kernel transaction manager. Its completion is
provisional until outer commit; rollback removes both Kernel and Application effects. It does not
select unrelated due work. See [Exact Intent execution](exact-intent-execution.md) for transaction,
replay, conflict, queue-processing, and external-side-effect guidance.

State readers must return the requested tenant, subject, descriptor and actual domain version.
Application mutations must use the same subject lock or an equivalent optimistic version check.
Missing or changed domain records invalidate queued work. Candidate values must survive storage
exactly: Kernel checks the accepted checksum both on acceptance and immediately before execution.
Historical reads, reconciliation and Undo query domain records using operation references.

Upgrade order: Kernel migrations through V5, Application migrations, then Kernel V6 and later.
Application migrations can recover necessary domain references from old receipts before the old
payload columns are removed. Preflight rejects unfinished Intents before Application migrations;
V6 checks again. Stop request admission, drain the queue using the old runtime, then stop old
processes. Test the upgrade on a restored database before production cutover. Never roll
an old runtime back onto the new schema.

Removing SQL columns removes the logical archive, not copies already present in backups, WAL,
replicas or old database pages. Their retention and physical disposal remain an operator task.
Execution metadata and checksums are not claimed to be anonymous.

Hot reads use Application-owned indexed models directly. For example, keep-in-touch CRM queries
`crm_contact_engagement_projection` for due contacts and Ledgerling queries
`ledger_filing_projection` for outstanding filings. Neither query reconstructs state from Kernel
Events or triggers evaluation.

Never add a generic entity, attribute, relationship, value, or JSONB catch-all domain store to the
Kernel. New domain state belongs in an Application migration. New Kernel migrations may contain
only execution, provenance, durability, authorisation, audit, lease, or reevaluation state.
