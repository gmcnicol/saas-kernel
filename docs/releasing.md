# Kernel releases

## Publication contract

GitHub Packages is the authoritative Maven registry:

```text
https://maven.pkg.github.com/gmcnicol/saas-kernel
```

A release contains exactly two coordinates at the same version:

```text
io.github.gmcnicol:saas-kernel:<version>
io.github.gmcnicol:saas-kernel-taxi-generator:<version>
```

Each includes the runtime/plugin JAR, sources, Javadocs, flattened consumer POM, and detached OpenPGP signatures. The parent reactor, acceptance-test contract, example Applications, and prototypes are not published. There is no third native coordinate: the runtime embeds the prepared library, ABI/build metadata, Cargo.lock, and committed Cedar patch. The original SDK remains a normal transitive dependency for the declared fallback route.

Do not publish fresh local SNAPSHOTs, replace released bytes, or infer availability from a tag alone. Maven Central is not the publication target for this pipeline; the earlier Central-only profile and registry lookup have been replaced.

## Version and review policy

The first release is **0.2.0**, matching the existing 0.2.0-SNAPSHOT development line without claiming 1.0 stability.

Before 1.0, patches preserve backwards compatibility; minor versions may add capabilities or explicitly document breaking changes. At 1.0 and later, incompatible changes require a major release. Keep runtime and generator at the same canonical, non-SNAPSHOT version.

A production/tooling change must include an explicit version increment in the reviewed PR. Update the root POM, owned parent references (including non-reactor prototypes), Application version properties, and the development classpath version fallback together. Add `docs/releases/<version>.md`, including any break and known limitation. Documentation-only changes may keep the version and do not publish. CI rejects reused/decreasing versions and inconsistent module versions. Binary compatibility is checked against the previous released runtime; an explicitly reviewed pre-1.0 minor or stable major change may permit a break, which still needs release-note explanation.

The reconciled source, previous review evidence, and the explicit **0.2.0 protected-read latency exception** are in [trusted-main-baseline.md](trusted-main-baseline.md). That exception does not turn a failed latency gate green or waive functional, security/public-contract, native, integrity, signing, or clean-consumer checks.

## Main-merge pipeline

`.github/workflows/build.yml` runs the same verification on PRs and pushes, but signing/publication is restricted to a version-changing **push to `main`** after verification and both Application image builds succeed. Fork PRs and non-main branches never receive signing material or package-write permission.

1. Run the standard-library release-tool tests and validate the version decision against the event's exact base SHA.
2. Run `clean verify` on Java 25.0.4, including public contracts, owned native tests, both packaged Application integration suites, and the isolated generated-binding consumer.
3. Check release files, flat POMs, and Java-21 generator bytecode. For a subsequent release, resolve the actual previous GitHub Packages version and run the configured binary-compatibility check.
4. Stage the exact already-tested eight artifact/POM files; do not rebuild them in the publisher. Record source commit/tree, SHA-256 hashes, independently expected native provenance, and the adoption manifest. The staging helper rejects dirty source, symlinks, changed/extra files, incorrect versions, and mismatched native/patch/lock identities.
5. After the image gate, download that same workflow artifact and validate it against the exact main commit. Probe all originals/signatures before writing. Reserve `v<version>` at that source SHA without moving an existing tag.
6. Import the dedicated release key only inside the publisher. Sign and deploy the tested files with Maven GPG plugin 3.2.8; package credentials come from the job's short-lived `GITHUB_TOKEN`, not a committed or operator-wide token.
7. Download every original and signature from the real registry, compare all original hashes, and verify signatures against the pinned dedicated signer.
8. Create a new external consumer directory and an empty Maven repository. Resolve both real coordinates, generate typed bindings, build and run the packaged adoption Application against PostgreSQL with the prepared backend, and compare the two downloaded JAR hashes to the original receipt. No staged JAR is installed into this repository.
9. Only then create the GitHub Release with the versioned source tag, release notes, signed `release.json`, signed `SHA256SUMS`, signed independent `kernel-adoption.properties`, and public key. Delete the runner's ephemeral private keyring even on failure.

The native route requires Linux x86_64/glibc >=2.36; the publication job uses Ubuntu 24.04. A passing Linux consumer does not certify other operating systems or hard native preemption. Producer test reports and the exact tested build payload have short, one-day Actions retention. Published receipts and Maven artifacts persist independently. Do not delete failed workflow runs or failed local measurement evidence.

## Signing and recovery

The initial public key is committed at `.github/release-key.asc`; its pinned fingerprint is:

```text
54C48E9BA81FE545D6122FB6B84C400A7E633730
```

The encrypted private key and passphrase are separate repository Actions secrets, `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE`. Only the trusted main publisher references them. The operator keeps an owner-only encrypted export, passphrase, fingerprint, and automatic revocation certificate under `~/.config/saas-kernel/release-signing/`; none is a repository/build artifact. The key expires after one year and must be rotated before expiry. Keep earlier public keys with their immutable release/tag assets when rotating.

Published coordinates are immutable. The preflight refuses different bytes or a partially populated version. If one module or sidecar was published before a failure, retain the failed run and source tag, retire that version, and make a reviewed patch-version release; never delete/redeploy it to repair bytes.

A failed publisher may be rerun **only using the original tested Actions artifact** when the registry is still empty or already contains all matching originals/signatures. Rerun the failed publisher job, not the successful build: a rebuilt archive is not assumed byte-identical. Existing matching tags may be reused, never moved. Authentication/server errors are not treated as absence. If GitHub Release asset upload fails after registry verification, inspect the existing release and recover missing metadata assets without clobbering existing files; a published release must not be silently rewritten. Do not interpret a failed publisher as a complete release.

## Consumer configuration

GitHub's Maven registry requires authentication even for public packages. A developer uses a read-only classic PAT with `read:packages` and any repository/organisation access required by GitHub. GitHub Actions consumers should use their own `GITHUB_TOKEN` with `packages: read`; verify access from the consuming repository rather than assuming cross-repository access. No native compilation or producer checkout belongs in a normal consumer build.

Configure dependency and plugin resolution:

```xml
<repositories><repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/gmcnicol/saas-kernel</url>
  <snapshots><enabled>false</enabled></snapshots>
</repository></repositories>
<pluginRepositories><pluginRepository>
  <id>github</id>
  <url>https://maven.pkg.github.com/gmcnicol/saas-kernel</url>
  <snapshots><enabled>false</enabled></snapshots>
</pluginRepository></pluginRepositories>
```

Use credentials in private Maven settings, never the POM:

```xml
<servers><server>
  <id>github</id>
  <username>${env.GITHUB_ACTOR}</username>
  <password>${env.GITHUB_TOKEN}</password>
</server></servers>
```

Set both dependency and generator plugin versions to `0.2.0`. `actions/setup-java` can create that server entry with `server-id: github`; provide `GITHUB_TOKEN` through the environment of the consuming Maven step and grant only `contents: read`/`packages: read`. The published consumer POMs have no unresolved reactor parent or unpublished production dependency.

Before adoption, verify the release's source tag, public signing-key fingerprint, detached signatures, JAR hashes, and `kernel-adoption.properties`. Regenerate bindings with the same-version plugin and check the packaged native/patch/lock identities, not just a declared property. `scripts/verify-published-consumer.py` is the release pipeline's empty-repository proof; ordinary consumers do not run the producer native build or use its private staging files.

## Useful local verification

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests
./mvnw --batch-mode --no-transfer-progress clean verify
python3 scripts/release.py check-version <exact-base-sha>
# On a clean checkout, after the build:
python3 scripts/release.py stage /new/private/external/release-directory
python3 scripts/release.py verify /new/private/external/release-directory
```

These are verification/staging commands, not publication or production deployment. Kernel publication does not update any Application until its separate consumer adoption is verified and merged.
