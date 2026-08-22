# Releasing

One release publishes exactly two same-version Maven Central artefacts:

- `io.github.gmcnicol:saas-kernel`, Java 25 runtime
- `io.github.gmcnicol:saas-kernel-taxi-generator`, Java 21 build-only Maven plugin

There is no starter, BOM, shared generated-binding artefact, or third runtime surface. Both modules inherit project URL, Apache-2.0 licence, developer, and SCM metadata. The build attaches source and Javadoc JARs, generates checksums, and signs release files.

Configure a Maven `settings.xml` server named `central` with a Sonatype Central user token. From a clean tag, run:

```shell
./mvnw clean verify
./mvnw -Prelease deploy
```

The release profile uses Sonatype Central Publishing Maven Plugin 0.9.0 and leaves publication manual after upload and validation. Review the bundle, confirm both artefacts have the same version, then publish it in Central Portal. Never put credentials in project files. See [Sonatype's Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/).

Binary compatibility enforcement starts after the first stable release has created a real baseline. This pre-release greenfield build deliberately carries no fictitious historical baseline.
