# SaaS Kernel

Java/Taxi/PostgreSQL/Cedar mechanisms for typed workflow Actions and current,
record/field-authorised reads and disclosures. Applications own their data, grants,
SQL/RLS, policy and integrations; Kernel is not an ORM, billing or user-management system.

## Start here

With Java 25 first on `PATH` and Docker running:

```sh
./mvnw --batch-mode -pl taxi-generator -am clean verify -Dinvoker.test=adoption
```

This builds and stages **matching packaged runtime + generator Maven artifacts** in
an isolated repository, generates a fresh consumer's Taxi bindings, packages/starts
that consumer and runs real PostgreSQL/Cedar examples: a restricted relationship
directory and independent practice staff/client visibility. No KIT source or
unpublished acceptance-contract dependency. **This command proves local staging;
it does not itself publish or prove registry availability.**

- [Adoption guide](docs/getting-started.md): trusted authentication, ownership, runnable code and egress
- [Sample source and checks](taxi-generator/src/it/adoption/README.md)
- Contracts: [capabilities](docs/effective-capabilities.md), [reads](docs/authorised-reads.md),
  [collections](docs/authorised-collections.md), [final sends](docs/fenced-disclosures.md)
- [Versions and migration](docs/migration-to-typed.md), [generator](docs/taxi-generator.md),
  [persistence](docs/persistence.md), [operators](docs/operators.md)
- [Architecture and downstream boundaries](docs/adr/0003-authorised-disclosure-review.md),
  [adversarial tests](docs/authorisation-contract-tests.md), [measured costs](docs/authorised-collection-measurements.md)

Full verification: `./mvnw --batch-mode clean verify`. There are exactly two release
artifacts: `io.github.gmcnicol:saas-kernel` and
`io.github.gmcnicol:saas-kernel-taxi-generator`, same version. Signed, immutable
releases use [GitHub Packages and the main-merge pipeline](docs/releasing.md), with
an empty-repository consumer proof before the release receipt is published.
See [0.2.1 deployment-guard fix and connection budget](docs/releases/0.2.1.md).
The [0.2.0 latency exception](docs/releases/0.2.0.md) remains historical evidence;
the original three-total-connection timing gate did not pass.
