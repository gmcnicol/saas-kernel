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
unpublished acceptance-contract dependency. **This is CI staged publication, not a
public Maven Central release.**

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
`io.github.gmcnicol:saas-kernel-taxi-generator`, same version. Actual public release
is separately authorised through [the existing release procedure](docs/releasing.md).
