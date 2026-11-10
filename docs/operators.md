# Operating an Application

Each Application is one executable Spring Boot JAR or standard JVM buildpack image. One process
contains HTTP delivery, Kernel runtime, Intent Worker, and Reevaluation Worker. PostgreSQL is the
only required external runtime service.

Configure the Application datasource as a `NOINHERIT`, non-owner, non-superuser,
non-`BYPASSRLS` login with `SET` membership in `kernel_runtime` and `kernel_worker`. Kernel selects
one least-privilege role inside each transaction. Use separate privileged Flyway credentials.

## Probes and shutdown

Use `/actuator/health/liveness` for liveness and `/actuator/health/readiness` for readiness on the
private management port. Liveness deliberately excludes PostgreSQL and exporters. Readiness
includes Spring readiness, PostgreSQL, completed Kernel and Application migrations, validated
assembly, both workers, and semantic deployment compatibility. Backlog and
ordinary failed Intent do not affect readiness.

On termination Spring first refuses readiness and HTTP traffic. Both workers stop new claims and
have 20 seconds to finish their current poll before interruption; Spring gives the lifecycle phase
30 seconds overall. HTTP requests and transactions use that same phase. Interrupted work rolls
back; its lease later expires and another replica reclaims it.

Rolling overlap is safe only when old and new processes have the same Semantic Pack checksum.
PostgreSQL session locks enforce this at startup. For a semantic change, scale old processes to
zero and wait for their readiness endpoints to disappear before starting the new version.

## Deployment-guard connection budget

From Kernel 0.3.0, reserve **one additional, non-pooled PostgreSQL connection per
Application instance** for the Semantic Pack deployment guard. The configured request
pool retains its full capacity: a maximum of three means three request sessions plus
one guard session, or four runtime sessions in total. Also account separately for
migration/admin sessions and rolling replicas. Do not hide the guard outside connection
capacity planning or return its live advisory lock to a request pool.

The default derives an independent connection from standard Spring JDBC datasources
or a JDBC-URL-configured Hikari datasource, retaining driver/SSL connection properties.
Credential/session-altering or opaque wrappers require explicit configuration.
Opaque datasources, custom pool subclasses,
JNDI/datasource-backed pools, rotating credentials and nontrivial session setup require
an explicit dedicated datasource rather than unsafe credential/property guessing.
Declare a non-pooled `DataSource` bean named `semanticDeploymentGuardDataSource` with
`@Bean(defaultCandidate = false)` so it does not replace the main application pool.
Use the same runtime database/login and required driver/SSL settings. The endpoint
must preserve a PostgreSQL session; transaction-pooling proxies cannot hold this lock.

The lock/exclusion protocol is unchanged. Losing the guard session still makes the
Application refuse readiness and close. A separately configured guard must not use
privileged migration credentials or an unrelated database.

## Required alerts

Applications must alert on:

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
- Evaluation Snapshot metadata, Action Offers, Intent, audit, ordered Execution Receipts,
  Projected State identities and reevaluation requests;
- Application-owned pending input and domain records, which contain the only domain values;
- Application tables referenced by Projected State or Event handling;
- tenant RLS policies, protected roles, grants, constraints, and lease state;
- Application, Kernel, Semantic Pack, and Authorisation Bundle provenance.

After restore, start the exact Application artefact recorded by restored provenance, let both
migration streams validate, and confirm private info, Flyway, readiness, and backlog metrics before
serving traffic. Do not edit stored checksums, status, Events, audit, or applied migrations to make
startup pass. Restore a correct backup or ship a forward migration.

## Version 0.2 cutover

Stop request admission, drain accepted work using the old version, then stop every old process.
The new Application must implement `ApplicationWorkflowData`; startup refuses to begin migration
without exactly one binding. Its migrations run after Kernel V5 and before V6, allowing recovery
of domain references needed for existing history or Undo. V6 removes historical domain payload
columns and aborts if any old Intent is pending, claimed or waiting to retry. This is not a rolling
upgrade, and rollback requires restoring the whole pre-upgrade database with the matching old
Application. Keep backup access restricted and follow the Application's data-retention policy;
the migration does not claim to erase backups, WAL or physical storage remnants.
