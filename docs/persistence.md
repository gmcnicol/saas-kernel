# Persistence boundary

`generic execution, specific data` is a Kernel invariant.

Applications own their canonical business data in ordinary domain-specific relational tables,
constraints, indexes, migrations, read models, and query services. Taxi defines what projected
values mean. It does not prescribe tables, columns, joins, materialised views, or storage engines.
An Application may supply Kernel Projected State from any normal Application-owned query shape.

Kernel persistence is control-plane evidence only: evaluation provenance, immutable Projected
State supplied for that evaluation, Action Offers, Intent, Events, audit, leases, and reevaluation.
Canonical JSON inside immutable Projected State, Fact, Candidate Payload, and Event evidence
preserves exact qualified Taxi identity, contract version, bytes, and checksum. It is not the
canonical business model and must not serve ordinary reads.

Hot reads use Application-owned indexed models directly. For example, keep-in-touch CRM queries
`crm_contact_engagement_projection` for due contacts and Ledgerling queries
`ledger_filing_projection` for outstanding filings. Neither query reconstructs state from Kernel
Events or triggers evaluation.

Never add a generic entity, attribute, relationship, value, or JSONB catch-all domain store to the
Kernel. New domain state belongs in an Application migration. New Kernel migrations may contain
only execution, provenance, durability, authorisation, audit, lease, or reevaluation state.
