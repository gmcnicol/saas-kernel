# #67 isolated review spike

Test-only Java/Taxi/PostgreSQL/Cedar evidence, outside the reactor; install/deploy
skipped. No production permissions or public Kernel interfaces change. See
[proposed ADR 0003](../../docs/adr/0003-authorised-disclosure-review.md).

With the existing Java 25 installation and Docker:

```sh
export JAVA_HOME=/home/gareth/.sdkman/candidates/java/25.0.4-tem
export PATH="$JAVA_HOME/bin:$PATH"
# Existing runtime/generator prerequisites, if not already installed locally:
./mvnw --batch-mode -pl kernel,taxi-generator -am -DskipTests install
./mvnw --batch-mode -f prototypes/authorisation-review/pom.xml clean test
```

Generated candidates/capability bindings are disposable `target/` output.
`CleanConsumer` and `DisclosureContracts` compile; final release is unimplemented.
`View<R>` is a deliberately string-map output sketch, **not final generated typed
output**. Nested leaves cover non-null scalar strings only.

Three real-DB tests cover tenant FORCE RLS, same-tenant Network/client record
isolation, selected nested leaves despite a parent permit, and expired/cross-scope
authority. Login is non-owner NOINHERIT/NOBYPASSRLS. SQL deliberately omits scope
predicates. Scope/authority comes from trusted test code, not authenticated HTTP.

The adapter opens its own transaction and rolls back/closes; it does not prove
caller role/tenant restoration in joined transactions. Constant resolver revisions
do not prove live revocation/fencing. Row/leaf checks occur after materialisation;
byte/time limits, pagination, nullable/list output and runtime release are absent.

`Baseline` is the already-completed manual measurement, not a test threshold.
It counts single Cedar calls, including denied records; **not batching**. Existing
binding has no batch interface, so #70 no-N+1 remains blocked. No further backend
or infrastructure exploration is part of this spike.
