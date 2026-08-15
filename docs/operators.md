# Operating an Application

Each Application is one executable Spring Boot JAR or standard JVM buildpack image. One process
contains HTTP delivery, Kernel runtime, Intent Worker, and Reevaluation Worker. PostgreSQL is the
only required external runtime service.

## Probes and shutdown

Use `/actuator/health/liveness` for liveness and `/actuator/health/readiness` for readiness on the
private management port. Liveness deliberately excludes PostgreSQL and exporters. Readiness
includes Spring readiness, PostgreSQL, completed Kernel and Application migrations, validated
assembly, both workers, semantic deployment compatibility, and fatal invariant state. Backlog and
ordinary failed Intent do not affect readiness.

On termination Spring first refuses readiness and HTTP traffic. Both workers stop new claims and
have 20 seconds to finish their current poll before interruption; Spring gives the lifecycle phase
30 seconds overall. HTTP requests and transactions use that same phase. Interrupted work rolls
back; its lease later expires and another replica reclaims it.

Rolling overlap is safe only when old and new processes have the same Semantic Pack checksum.
PostgreSQL session locks enforce this at startup. For a semantic change, scale old processes to
zero and wait for their readiness endpoints to disappear before starting the new version.

## Required alerts

Applications must alert on:

- fatal invariant count above zero;
- sustained readiness failure or repeated startup/migration failure;
- oldest due Intent and reevaluation age;
- growing Intent or reevaluation backlog;
- terminal failure and retry-exhaustion rates;
- lease loss, expiry, and reclaim rates;
- processing, handler, evaluation, authorisation, and presentation latency/error rates;
- PostgreSQL availability, connection saturation, storage, replication, and backup failure;
- telemetry exporter failure when loss of operational visibility breaches Application policy.

Each Application owns thresholds, evaluation windows, paging routes, dashboards, exporters,
sampling, backup schedule, retention, partitioning, vacuum, capacity, replication, and physical
PostgreSQL operation.

## Whole-database recovery

Restore one transactionally consistent PostgreSQL backup. Never restore Kernel tables alone or
rebuild only Projected State. A valid recovery preserves together:

- both Flyway schema-history tables and every applied migration checksum;
- immutable Evaluation Snapshots, Action Offers, Intent, audit, ordered Events, Event payloads,
  Projected State versions, and reevaluation requests;
- Application tables referenced by Projected State or Event handling;
- tenant RLS policies, protected roles, grants, constraints, and lease state;
- Application, Kernel, Semantic Pack, Authorisation Bundle, and Presentation Pack provenance.

After restore, start the exact Application artefact recorded by restored provenance, let both
migration streams validate, and confirm private info, Flyway, readiness, and backlog metrics before
serving traffic. Do not edit stored checksums, status, Events, audit, or applied migrations to make
startup pass. Restore a correct backup or ship a forward migration or compatibility adapter.
