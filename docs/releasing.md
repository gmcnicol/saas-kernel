# Releasing

One release publishes exactly two same-version Maven Central artefacts:

- `io.github.gmcnicol:saas-kernel`, Java 25 runtime
- `io.github.gmcnicol:saas-kernel-taxi-generator`, Java 21 build-only Maven plugin

There is no starter, BOM, shared generated-binding artefact, or third runtime surface. Both modules inherit project URL, Apache-2.0 licence, developer, and SCM metadata. The build attaches source and Javadoc JARs, generates checksums, and signs release files.

## CI staged artifact proof (not a public release)

```shell
./mvnw --batch-mode -pl taxi-generator -am clean verify -Dinvoker.test=adoption
```

The existing Maven Invoker `install`/`run` lifecycle installs the matching packaged
runtime and generator JARs and flattened consumer POMs into
`taxi-generator/target/local-repository`, then forks a fresh Maven consumer against
those coordinates. The consumer generates bindings, packages/starts its own JAR and
runs real PostgreSQL/Cedar read/collection/workflow/disclosure tests. It does not
use workspace runtime classes or the unpublished acceptance-contract. The normal
CI `clean verify` includes this fixture and the existing positive/negative consumers.
See the [sample](../taxi-generator/src/it/adoption/README.md).

This isolated staged publication is the approved #73 proof boundary. It is neither
an upload to Sonatype nor evidence that these versions are publicly available.
The sample is test-only; exactly the two artifacts listed above remain releasable.

## Separately authorised public release

Only after explicit public-release authorisation, configure a Maven `settings.xml`
server named `central` with a Sonatype Central user token. From a clean tag, run:

```shell
./mvnw clean verify
./mvnw -Prelease deploy
```

The release profile uses Sonatype Central Publishing Maven Plugin 0.9.0 and leaves publication manual after upload and validation. Review the bundle, confirm both artefacts have the same version, then publish it in Central Portal. Never put credentials in project files. See [Sonatype's Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/).

Binary compatibility enforcement starts after the first stable release has created a real baseline. This pre-release greenfield build deliberately carries no fictitious historical baseline.
