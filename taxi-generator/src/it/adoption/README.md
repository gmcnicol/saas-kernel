# Fresh packaged Maven consumer

From the repository root, with **Java 25 first on PATH and Docker running**:

```sh
./mvnw --batch-mode -pl taxi-generator -am clean verify -Dinvoker.test=adoption
```

One command builds/stages matching runtime and generator JARs, generates this
consumer, packages it and starts it from `adoption-1.0.0.jar`. Five Failsafe tests
use disposable PostgreSQL 18 with non-owner roles, Application migrations/FORCE RLS
and real Cedar. No running application/database setup or credentials are needed.
No new test library, external provider SDK or acceptance-contract dependency.

This is an independent Boot-parent Maven POM, not a reactor child. Invoker filters
`@project.version@` to the version it stages in `target/local-repository`; its fork
resolves both Maven coordinates there. Tests assert staged runtime JAR origin,
matching generated/runtime versions, packaged consumer origin, successful readiness
and absence of the unpublished acceptance-contract. The hook also requires both
staged JARs and five executed passing tests. Generated classes are never committed.
This sample JAR is a **test artifact**, not a third Kernel release artifact.
CI staged publication is **not public Maven Central publication**.

## Read these files in order

1. `pom.xml`: same-version runtime/plugin, ordinary Maven generation/packaging,
   checksums and packaged Failsafe classpath.
2. `src/main/resources/semantic-pack/schema.taxi`: independent directory and practice
   types, scope Subjects and capabilities. No KIT sources or billing dependencies.
3. `src/main/java/example/AdoptionApplication.java`: complete bean/slot registration,
   explicit minimal policy inputs/required capabilities and current immutable policy pin.
4. `CurrentCapabilities.java`: current SQL grant/revision reads, expiry and alternative
   preview/admin/paid sources collapsed to one effective capability. The `actor` value
   is the trusted requested principal ID; commercial provenance is not passed to Cedar.
5. `DirectoryQueries.java`: typed read with deliberately broad SQL (negative example).
   RLS contains other tenants; Kernel/Cedar remove other scopes/owners and private notes.
   Production detail queries should additionally bind record/scope, not scan the directory.
6. `PracticeQueries.java`: separate staff/assigned-client relation, bounded reference-
   prefix search, full declared unique ordering and exact authorised count/index pins.
   `practice_view` revisions are per principal; trusted writers must maintain them
   atomically on relevant changes, never invalidate clients for hidden-only changes.
7. `DirectoryWorkflowData.java`: one Application-owned pending/applied review note;
   no duplicate Kernel domain payloads.
8. `src/main/resources/db/application/migration` and `authorisation`: authored SQL,
   grants, FORCE RLS and independent Cedar record/leaf/query rules.
9. `src/test/java/example/AdoptionIT.java`: runnable calls to the public read,
   collection, workflow and disclosure boundaries. Includes deny-by-default, wrong
   SQL, source parity/overlap, action execution, policy/index staleness, minimal
   Assistant context, buffered-answer revocation and a separate source-reference export.

Principals/scopes are **trusted test authentication fixtures**, not browser inputs.
`Staff`/`Client`/`Member` are this Application's schema, not Kernel roles. Migration
and seed credentials and the sample telemetry key are disposable local examples.
There is no web server, auth framework, production grant-writer/job service, paid
integration or general-purpose DAO/query abstraction to adopt.

Test logs: `taxi-generator/target/it/adoption/build.log` and its
`target/failsafe-reports`. Existing CI `clean verify` runs this fixture automatically.
For a standalone copy, pin `kernel.version` to an actually available matching pair
(or use this isolated staging repository); the source POM's invoker token is not a
public Maven version. See [the adoption guide](../../../../docs/getting-started.md)
for trust, migration, failure and bounded-subset limits, and
[releasing](../../../../docs/releasing.md) for separately authorised public release.
