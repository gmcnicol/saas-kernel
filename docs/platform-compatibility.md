# Platform compatibility

## Scope and release status

This is the current qualification record for the **retained prepared scalar JNI bridge**, not a promise that every platform included in the Cedar SDK works with Kernel.

The executed checks below used the proposed advisory implementation at lifecycle-hardened source `5d004b595d19c08280b82d120063b18db2e08fe6`, Java SDK 4.11.0 without upstream caching, and the retained bridge with unchanged Cedar engine base/patch and ABI. Earlier qualification at `5724be6` was repeated against the final rebuilt artifact; the [qualification receipt](jni-qualification.md) records the lifecycle fix and new bytes. The private runtime JAR still carries 0.3.0 metadata but **is not the signed released 0.3.0**. Published 0.3.0 retains SDK 4.2.3; these new results do not silently recertify that release or authorise publication/deployment.

Downstream Applications may remain pinned to released 0.3.0 and adopt a later Kernel separately. Actual KIT adoption is not a prerequisite for this qualification. Kernel still requires matching packaged runtime/generator and independent consumer checks.

## Runtime compatibility matrix

| Runtime target | `auto` selection | Current execution evidence |
| --- | --- | --- |
| Linux x86_64, **glibc 2.36** | Prepared scalar JNI | Packaged-JAR functional proof passed on Debian Bookworm userspace with Temurin 25.0.4. Explicit `prepared` and `sdk` also passed. This verifies the declared libc floor, not a minimum kernel/CPU or platform-specific performance guarantee. |
| Linux x86_64, **glibc 2.43** | Prepared scalar JNI | Host clean producer/security/public-contract suite and packaged-JAR functional proof passed with Temurin 25.0.4. |
| Linux x86_64, **glibc 2.35** | SDK compatibility route | Packaged-JAR proof passed on Ubuntu Jammy userspace with Temurin 25.0.5. Forced `prepared` and direct owned-native loading rejected the unsupported ABI. Both `auto` SDK fallback and explicit `sdk` passed functional checks. No prepared performance/resource guarantees apply to this route. |
| Linux x86_64, other glibc versions | Prepared only when the probe reports ≥2.36; otherwise SDK is attempted | Not independently executed. Neither the owned binary's GLIBC_2.34 symbols nor the SDK binary's GLIBC_2.18 symbols establish a supported lower runtime floor. |
| Linux ARM64/glibc | SDK is attempted | SDK ARM64 binary is included; no owned prepared binary. **Not executed or certified.** |
| macOS Apple Silicon | SDK is attempted | SDK ARM64 binary is included; no owned prepared binary. **Not executed or certified.** |
| macOS Intel | SDK is attempted | SDK x86_64 binary is included; no owned prepared binary. **Not executed or certified.** |
| **Alpine/musl x86_64** | Prepared ABI rejected; SDK startup attempted | **Unsupported in the tested configuration.** On unmodified Temurin 25.0.4 Alpine, the owned ABI gate rejected correctly and both `auto`/`sdk` failed with `UnsatisfiedLinkError: no cedar_java_ffi in java.library.path`. No glibc shim was installed. |
| Windows x86_64 | SDK is attempted | SDK DLL is included; no owned prepared binary. **Not executed or certified.** |

An included binary is an inventory fact, not working-platform evidence. ARM emulation, Rosetta, libc compatibility shims and different JVM architectures are separate configurations; none was used to claim native-platform support here.

The packaged-JAR proof checks actual runtime identity, validated policies, allow/deny, forbid precedence, an errored forbid failing closed, typed references and implicit-map rejection. It closes and reopens the evaluator three times, checking that prepared handles, bundles, retained charges, queues and workers drain. This is a functional ABI/routing proof, not the entire Application suite or a latency/RSS certification on every row.

## Backend selection and failures

Set `kernel.authorisation.read-evaluator`:

- **`auto`**: select prepared scalar only for Linux x86_64 (`amd64` or `x86_64`) with detected GNU libc ≥2.36. Otherwise attempt the explicitly identified SDK compatibility route.
- **`prepared`**: require the supported owned ABI and packaged binary. An unsupported target fails assembly; this does not provide a portable SDK fallback.
- **`sdk`**: explicitly select the uncached SDK route. It remains native code and must load the SDK's platform binary; it is not a pure-Java or universally portable mode.

Inspect `kernel.read.authorization.backend{backend=...}` for `prepared-scalar`, `sdk-explicit` or `sdk-unsupported-platform`. Do not infer the active backend from configuration alone.

**There is no fallback after a supported prepared path fails.** A missing/corrupt resource, digest or identity mismatch, extraction problem or native load failure fails assembly. Evaluation failures fail closed rather than retrying through the SDK. `auto` does not promise that the SDK can start on an otherwise unsupported platform; Alpine demonstrates that distinction.

SDK compatibility does not inherit prepared latency, bounded native cache/queue/worker or cancellation guarantees. Full validation, clean diagnostics and current authority/release fences remain required on every selected route. Upstream SDK caching stays disabled because it drops schema warnings.

## JVM, container and native-loading requirements

- Use **Java 25**. Qualification used Temurin 25.0.4, and 25.0.5 in the below-floor SDK lane. Java 21 generator bytecode does not make the runtime Java 21-compatible; newer JVM lines are not qualified by these results.
- Permit native access, for example `--enable-native-access=ALL-UNNAMED` for classpath Applications. Java's native-access warning is not a portability or security waiver.
- For the prepared route, use a glibc-based x86_64 image at or above 2.36, with the GNU dynamic loader and required `libc`/`libgcc_s` libraries. Debian Bookworm is the executed minimum-userspace example. A generic `linux/amd64` image label does not establish its libc.
- Supply a writable temporary directory that permits native executable mappings. Kernel creates a private 0700 directory/0600 library, verifies bounded bytes/digest/identity, loads it and unlinks it. A `noexec` temporary mount can prevent loading even though executable file mode is not required. Set `-Djava.io.tmpdir=...` to an appropriate private runtime location when necessary.
- The minimum-libc proof passed with a read-only root filesystem, no network, dropped capabilities, `no-new-privileges`, a writable executable `/tmp` and an unprivileged UID. This is not certification for every seccomp/container policy.
- The SDK also loads/extracts a native library, including when Kernel uses the prepared route for reads: schema/policy construction and public schema conversion still use the SDK. Qualifying only `libkernel_cedar.so` is insufficient.
- Applications consume the existing same-version runtime/generator pair. **Consumers do not compile Rust or need Docker merely to run Kernel.** Building Kernel's owned binary remains a separate Linux-amd64 producer task using the pinned Rust/Docker build in `kernel/native/build.sh`.

Do not select Alpine/musl expecting `auto` to rescue startup. Use an executed glibc configuration or qualify a different target explicitly; do not add an undocumented libc shim.

## Evidence and reproduction

All Linux container checks reused the same packaged runtime and its resolved runtime dependency JARs, not reactor `target/classes`. Container mounts were read-only and no producer build ran inside the runtime checks. `NativePlatformProbe.java` under `kernel/src/test/java/io/github/gmcnicol/kernel/internal/` is the assert-based probe; compile it separately against the staged runtime JAR/dependencies and launch a fresh JVM for each mode.

```sh
javac -cp 'proof/lib/*' -d proof/classes \
  kernel/src/test/java/io/github/gmcnicol/kernel/internal/NativePlatformProbe.java
java --enable-native-access=ALL-UNNAMED -cp 'proof/classes:proof/lib/*' \
  io.github.gmcnicol.kernel.internal.NativePlatformProbe \
  true auto prepared-scalar "$RUNTIME_SHA256"
```

Arguments are expected prepared-ABI support, selection, expected backend meter tag and exact packaged runtime SHA-256. `gate` checks direct owned-native rejection before SDK initialization; `reject` checks forced prepared selection on an SDK-loadable unsupported ABI. `false auto sdk-unsupported-platform` and `false sdk sdk-explicit` exercise real SDK fallback rather than spoofed OS properties. A failed process is not a passing fallback test.

Qualification inputs on 11 November 2026:

```text
private runtime SHA256:
242b1fb529dca0caf7908dbcf47ad8a38d21719bf8ab38efd3162a24a9520ec1
owned native SHA256:
8b9d83e3cf67321c0afa536da64f0f32c8c28a30e1ccb05c86fe288d9e1efbe1
SDK 4.11.0 uber SHA256:
cc841f8ec352dee0330b5137abcdaf76c35be5892e56e7a810db964b1fc08b57
Bookworm userspace image:
rust@sha256:3914072ca0c3b8aad871db9169a651ccfce30cf58303e5d6f2db16d1d8a7e58f
Jammy/JVM image:
eclipse-temurin@sha256:fd1b2330c12e88c3269a45d4be996efa07e246b67c3a716c737b1e22b1cc55b4
Alpine/JVM image:
eclipse-temurin@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682
```

The Bookworm image supplied userspace only; the already installed Temurin 25.0.4 directory was mounted read-only. Containers share the host kernel and do not establish oldest-kernel support. Private execution logs are retained under `$HOME/.cache/kernel-jni-validation-20261111/proof-final/`: `host-{auto,prepared,sdk}.log`, `glibc-236.log`, `glibc-235.log` and `musl.log`. Checksums identify exact observed inputs, not independently attested reproducible builds or public release availability.

Still required before broader support claims: actual Linux ARM64/Apple Silicon/Intel Mac execution; platform-specific Application/consumer suites; minimum kernel/CPU qualification where promised; and controlled performance/lifecycle qualification beyond these smoke checks. No unavailable hardware lane is converted into a pass by skipping tests.

See [prepared scalar details](prepared-cedar.md), [advisory evidence](cedar-advisory-assessment.md) and [operator requirements](operators.md). The earlier advisory assessment's platform inventory predates these executed minimum/below-floor/musl checks; this document is the current compatibility matrix.
