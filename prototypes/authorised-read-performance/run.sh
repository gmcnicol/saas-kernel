#!/usr/bin/env bash
# THROWAWAY. Nothing is installed into the shared Maven repository or published.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
KIT=${KIT_PERF_WORKTREE:-/home/gareth/src/kitt-worktrees/read-performance}
OUT=${PERF_ROOT:-/tmp/spec85-perf}
export JAVA_HOME=${JAVA_HOME:-/home/gareth/.sdkman/candidates/java/25.0.4-tem}
export PATH="$JAVA_HOME/bin:$PATH"
export PLAYWRIGHT_HOST_PLATFORM_OVERRIDE=ubuntu24.04-x64
CEDAR=4dea3e60201f92139141d65c4a52966e056b007d
IMAGE=rust@sha256:3914072ca0c3b8aad871db9169a651ccfce30cf58303e5d6f2db16d1d8a7e58f
PROTO="$ROOT/prototypes/authorised-read-performance"
LIB="$OUT/native-target/release/libthrowaway_prepared_cedar.so"
mkdir -p "$OUT"
[[ $(git -C "$ROOT" branch --show-current) == prototype/authorised-read-performance ]]
[[ $(git -C "$KIT" branch --show-current) == prototype/85-read-performance ]]
git -C "$KIT" merge-base --is-ancestor 71dbd63 HEAD
if [[ ! -d "$OUT/m2" ]]; then
  # Dereference copies, never write through symlinks back to the shared cache.
  cp -aL --reflink=auto "$HOME/.m2/repository" "$OUT/m2"
fi
if find "$OUT/m2" -type l -print -quit | grep -q .; then echo 'Refusing Maven repo containing symlinks' >&2; exit 1; fi
mvn=("$ROOT/mvnw" -o -q "-Dmaven.repo.local=$OUT/m2")
run() { local name=$1; shift; echo "$(date -Is) START $name"; timeout 10m "$@" > "$OUT/$name.log" 2>&1; echo "$(date -Is) END $name"; }
build() {
  if [[ ! -d "$OUT/cedar" ]]; then
    gh api "repos/cedar-policy/cedar/tarball/$CEDAR" > "$OUT/cedar.tar.gz"
    mkdir "$OUT/cedar"; tar -xzf "$OUT/cedar.tar.gz" -C "$OUT/cedar" --strip-components=1
  fi
  python3 - "$OUT/cedar/cedar-policy/src/ffi/is_authorized.rs" "$PROTO/prepared-api.rs" <<'PY'
import sys
p, patch = sys.argv[1:]
s = open(p).read().split('// THROWAWAY extension')[0]
open(p, 'w').write(s + open(patch).read())
PY
  mkdir -p "$OUT/cargo" "$OUT/native-target"
  run native-build docker run --rm --cpus=3 -v "$OUT/cedar:/cedar" -v "$PROTO/native:/work" -v "$OUT/cargo:/cache" -v "$OUT/native-target:/target" -w /work -e CARGO_HOME=/cache -e CARGO_TARGET_DIR=/target -e RUSTFLAGS=-Atext_direction_codepoint_in_literal "$IMAGE" cargo build --locked --release -j 3
  cd "$ROOT"
  run private-package "${mvn[@]}" -pl kernel,taxi-generator -am install -DskipTests -Dinvoker.skip=true -Dmaven.javadoc.skip=true
  { git rev-parse HEAD; git -C "$KIT" rev-parse HEAD; echo "cedar=$CEDAR image=$IMAGE"; java -version 2>&1; ldd --version | head -1; sha256sum "$LIB" "$PROTO/native/Cargo.lock" "$PROTO/prepared-api.rs" "$OUT/m2/io/github/gmcnicol/saas-kernel/0.2.0-SNAPSHOT/saas-kernel-0.2.0-SNAPSHOT.jar" "$OUT/m2/io/github/gmcnicol/saas-kernel-taxi-generator/0.2.0-SNAPSHOT/saas-kernel-taxi-generator-0.2.0-SNAPSHOT.jar" "$HOME/.m2/repository/com/cedarpolicy/cedar-java/4.2.3/cedar-java-4.2.3-uber.jar"; } > "$OUT/provenance.txt"
}
verify() {
  cd "$ROOT"
  run evaluator-green "${mvn[@]}" -pl kernel -am test -Dtest=PreparedReadEvaluatorExperiment -Dsurefire.failIfNoSpecifiedTests=false "-Dkernel.prototype.native=$LIB"
  for mode in sdk scalar batch; do
    run "kernel-$mode-contract" "${mvn[@]}" -pl applications/keep-in-touch-crm -am test -Dtest=AuthorisedReadTests -Dsurefire.failIfNoSpecifiedTests=false "-Dkernel.prototype.evaluator=$mode" "-Dkernel.prototype.native=$LIB"
    mkdir -p "$OUT/reports/kernel-$mode"; cp "$ROOT"/applications/keep-in-touch-crm/target/surefire-reports/*AuthorisedReadTests* "$OUT/reports/kernel-$mode/"
    cd "$KIT"
    run "kit-$mode-contract" "${mvn[@]}" -Dtest=PrivateContactReadTests,PrivateCollectionsTests,PrivateReadContractTests,PrivateAuthorityEpochTests,AssistantDisclosureTests "-Dkernel.prototype.evaluator=$mode" "-Dkernel.prototype.native=$LIB" test
    mkdir -p "$OUT/reports/kit-$mode"; cp target/surefire-reports/*Tests.xml "$OUT/reports/kit-$mode/"
    cd "$ROOT"
  done
}
kit() {
  cd "$KIT"
  for mode in scalar batch sdk-profile sdk; do
    run "kit-$mode-measured" "${mvn[@]}" "-Dkernel.prototype.evaluator=$mode" "-Dkernel.prototype.native=$LIB" -Dtest=PrivateReadBenchmark package
    mkdir -p "$OUT/reports/kit-$mode-measured"; cp target/surefire-reports/*PrivateReadBenchmark* "$OUT/reports/kit-$mode-measured/"
    sha256sum target/kitt-saas-0.0.1-SNAPSHOT.jar > "$OUT/kit-$mode-package.sha256"
  done
}
kernel() {
  cd "$ROOT"
  for mode in sdk scalar batch; do
    run "kernel-$mode-measured" "${mvn[@]}" -pl applications/keep-in-touch-crm -am test -Dtest=CollectionReadBenchmark#measure -Dsurefire.failIfNoSpecifiedTests=false "-Dkernel.prototype.evaluator=$mode" "-Dkernel.prototype.native=$LIB"
  done
}
proof() {
  cd "$KIT"
  local runtime generator
  runtime=$(sha256sum "$OUT/m2/io/github/gmcnicol/saas-kernel/0.2.0-SNAPSHOT/saas-kernel-0.2.0-SNAPSHOT.jar" | cut -d' ' -f1)
  generator=$(sha256sum "$OUT/m2/io/github/gmcnicol/saas-kernel-taxi-generator/0.2.0-SNAPSHOT/saas-kernel-taxi-generator-0.2.0-SNAPSHOT.jar" | cut -d' ' -f1)
  run kit-proof-package "${mvn[@]}" package -DskipTests
  run kit-packaged-proof "${mvn[@]}" -Dtest=PackagedPrivateReadProof "-Dkernel.prototype.runtime.sha=$runtime" "-Dkernel.prototype.generator.sha=$generator" -Dkernel.prototype.evaluator=batch "-Dkernel.prototype.native=$LIB" test
  cp target/packaged-private-*.log "$OUT/"
}
runtime() {
  mkdir -p "$OUT/linux-smoke"
  local cp
  cp=$(python3 - "$ROOT/kernel/target/surefire-reports/TEST-io.github.gmcnicol.kernel.internal.PreparedReadEvaluatorExperiment.xml" <<'PY'
import sys, xml.etree.ElementTree as E
print(next(p.attrib['value'] for p in E.parse(sys.argv[1]).getroot().find('properties') if p.attrib['name'] == 'java.class.path'))
PY
)
  javac -cp "$cp" -d "$OUT/linux-smoke" "$PROTO/NativeRuntimeSmoke.java"
  run noble-runtime-smoke docker run --rm --network none --user "$(id -u):$(id -g)" -v "$OUT:$OUT:ro" -v "$ROOT:$ROOT:ro" -v "$JAVA_HOME:$JAVA_HOME:ro" ubuntu@sha256:4fbb8e6a8395de5a7550b33509421a2bafbc0aab6c06ba2cef9ebffbc7092d90 "$JAVA_HOME/bin/java" "-Dkernel.prototype.native=$LIB" -cp "$OUT/linux-smoke:$cp" io.github.gmcnicol.kernel.internal.NativeRuntimeSmoke
}
case ${1:-all} in
  build) build;; verify) verify;; kit) kit;; kernel) kernel;; proof) proof;; runtime) runtime;;
  all) build; verify; kit; kernel; proof; runtime; python3 "$PROTO/summarize.py" "$OUT" --gate;;
  *) echo "Usage: $0 [all|build|verify|kit|kernel|proof|runtime]" >&2; exit 2;;
esac
