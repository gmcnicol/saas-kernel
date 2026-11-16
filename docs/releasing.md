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

A production/tooling change must include an explicit version increment in the reviewed PR. Update the root POM, owned parent references (including non-reactor prototypes), Application version properties, and the development classpath version fallback together. Add `docs/releases/<version>.md`, including any break and known limitation. Documentation-only changes may keep the version and do not publish. CI rejects reused/decreasing versions and inconsistent module versions. Binary compatibility is checked against the last completed GitHub Release's runtime, independently of the base POM's version-ordering check; an explicitly reviewed pre-1.0 minor or stable major change may permit a break, which still needs release-note explanation.

The reconciled source, previous review evidence, and the explicit **0.2.0 protected-read latency exception** are in [trusted-main-baseline.md](trusted-main-baseline.md). That exception does not turn a failed latency gate green or waive functional, security/public-contract, native, integrity, signing, or clean-consumer checks.

## Main-merge pipeline

Build once before merge, test that output, then promote it unchanged. `.github/workflows/build.yml` builds PR candidates; `.github/workflows/release.yml` publishes a version-changing **push to `main`** without building the producer again. Forks and non-main branches never receive signing material or package-write permission.

1. Run the standard-library release/promotion tests and validate the version decision against the PR's exact base SHA.
2. Run **one `clean install`** on Java 25.0.4. Maven includes `verify` before installation: native tests, public contracts, both Applications and the isolated generated-binding consumer execute before the build succeeds. Installation copies those exact artifacts into the job's Maven repository; it is not a second build.
3. Check distributable files, flattened POMs and Java-21 generator bytecode, then compare the tested runtime against the last completed GitHub Release. Missing/authentication/server failures retain their existing fail-closed behavior; an abandoned partial version is not the baseline.
4. Build the CRM/Ledgerling acceptance-fixture images in the **same job**, using `spring-boot:build-image-no-fork` and the existing executable JARs. Hash the JARs before/after and fail if either changes. These images are packaging checks, not deployed Kernel products. No second reactor install, compilation or package lifecycle runs.
5. Stage one immutable release bundle containing the exact eight runtime/generator artifact/POM files, original build commit/tree, hashes, native provenance and adoption manifest. Upload it as `kernel-release-<build-sha>-<run-attempt>`. All checks must succeed before the run becomes eligible for promotion.
6. On main, `scripts/promote.py` requires the matching merged same-repository PR and a successful `build.yml` PR run completed before merge. It selects only that successful run attempt's unexpired artifact. Missing, failed, foreign, forked, late, expired or ambiguous candidates stop publication; main does not fall back to rebuilding.
7. Download outside the checkout, fetch the exact tested commit, and require its Git tree to equal merged main's tree. Only then check out that tested commit and run the existing strict receipt/payload/native verification. A merge commit may have a different SHA, but no source-file difference is allowed. The original receipt and bytes are not rewritten. **The release tag and signed source/adoption identity point to the actual tested build commit**, not a newly built main commit.
8. Probe immutable registry state, reserve that tag without moving an existing tag, import the dedicated signer, and sign/deploy the tested files using Maven GPG `sign-and-deploy-file`. No producer lifecycle runs in publication.
9. Download the actual registry files/signatures, compare their original hashes, verify signatures, and run the independent empty-repository packaged consumer. Only that consumer Application is built here; neither producer coordinate is rebuilt or substituted. Publish the GitHub Release and signed receipts only after this proof. Remove the ephemeral private keyring even on failure.

The native route requires Linux x86_64/glibc >=2.36; the publication job uses Ubuntu 24.04. A passing Linux consumer does not certify other operating systems or hard native preemption. Producer test reports and the exact tested build payload have short, one-day Actions retention. Published receipts and Maven artifacts persist independently. Do not delete failed workflow runs or failed local measurement evidence.

## Signing and recovery

The initial public key is committed at `.github/release-key.asc`; its pinned fingerprint is:

```text
54C48E9BA81FE545D6122FB6B84C400A7E633730
```

The encrypted private key and passphrase are separate repository Actions secrets, `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE`. Only the trusted main publisher references them. The operator keeps an owner-only encrypted export, passphrase, fingerprint, and automatic revocation certificate under `~/.config/saas-kernel/release-signing/`; none is a repository/build artifact. The key expires after one year and must be rotated before expiry. Keep earlier public keys with their immutable release/tag assets when rotating.

Published coordinates are immutable. The preflight refuses different bytes or a partially populated version. If one module or sidecar was published before a failure, retain the failed run and source tag, retire that version, and make a reviewed patch-version release; never delete/redeploy it to repair bytes.

A failed publisher may be rerun **only using the original tested pre-merge Actions artifact** when the registry is still empty or already contains all matching originals/signatures. Rerun the failed publisher job, not the successful build: a rebuilt archive is not assumed byte-identical. Existing matching tags may be reused, never moved. Authentication/server errors are not treated as absence. If GitHub Release asset upload fails after registry verification, inspect the existing release and recover missing metadata assets without clobbering existing files; a published release must not be silently rewritten. Do not interpret a failed publisher as a complete release.

## Deprecation and withdrawal

If a complete release is defective, withdraw it by an explicit notice rather than replacing or deleting its Maven bytes. Add a prominent withdrawal notice to its GitHub Release and current adoption documentation, naming the affected exact version, impact, issue, and replacement version when available. Preserve its original tag, receipts, signatures, and failed-run evidence. Notify known consumers through their issue trackers; for Kit, track and verify the exact replacement adoption separately. Publish a reviewed compatible patch (or an explicitly documented minor/major break) and give exact-version upgrade guidance. A withdrawn complete release remains part of compatibility history; it must not make an ordinary patch silently skip compatibility checks.

GitHub Maven has no routine automated yank operation in this pipeline. A security takedown or actual package deletion needs a separate explicit owner decision, coordinated disclosure, and retained evidence. Revoke/rotate the signing key only if the key itself is compromised; withdrawing an Application/runtime defect does not invalidate unrelated signed releases.

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

Set both dependency and generator plugin versions to `0.3.3`. Cedar advisory boundary fixes require 0.3.1 or later; the earlier 0.2.x/0.3.0 coordinates retain the affected SDK and must not be treated as patched. `actions/setup-java` can create that server entry with `server-id: github`; provide `GITHUB_TOKEN` through the environment of the consuming Maven step and grant only `contents: read`/`packages: read`. The published consumer POMs have no unresolved reactor parent or unpublished production dependency.

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
