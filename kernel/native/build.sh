#!/usr/bin/env bash
# Build native resources inside the existing runtime artifact; never install a host toolchain.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1
CACHE=${KERNEL_NATIVE_CACHE:-"$HERE/../target/native-cache"}
IMAGE=rust@sha256:3914072ca0c3b8aad871db9169a651ccfce30cf58303e5d6f2db16d1d8a7e58f
BASE=09e21d967f1da2cf2abbe1e0b0b2067b41867735
PATCH="$HERE/patches/prepared-condition.patch"
PATCH_SHA=$(sha256sum "$PATCH" | cut -d' ' -f1)
mkdir -p "$CACHE/cargo" "$CACHE/target" "$OUT/META-INF/saas-kernel/native/linux-x86_64"
CACHE=$(cd "$CACHE" && pwd)
# Read only a verified base object; never patch a Cargo checkout or a shared SDK.
REPO=
for candidate in "$CACHE"/cargo/git/db/* "$CACHE/cedar-base.git"; do
  if git --git-dir="$candidate" cat-file -e "$BASE^{commit}" 2>/dev/null; then
    REPO=$candidate
    break
  fi
done
if [[ -z "$REPO" ]]; then
  REPO="$CACHE/cedar-base.git"
  git init --bare "$REPO"
  git --git-dir="$REPO" fetch --no-tags https://github.com/cedar-policy/cedar "$BASE"
fi
WORK=$(mktemp -d "$CACHE/build-XXXXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/cedar"
git --git-dir="$REPO" archive "$BASE" | tar -x -C "$WORK/cedar"
# Also work when CACHE is below this repository: do not discover its Git root.
GIT_CEILING_DIRECTORIES="$WORK" git -C "$WORK/cedar" apply --check "$PATCH"
GIT_CEILING_DIRECTORIES="$WORK" git -C "$WORK/cedar" apply "$PATCH"
GIT_CEILING_DIRECTORIES="$WORK" git -C "$WORK/cedar" apply --reverse --check "$PATCH"
cp "$HERE/Cargo.toml" "$HERE/Cargo.lock" "$WORK/"
cp -R "$HERE/src" "$WORK/src"
docker run --rm --platform linux/amd64 --cpus=3 --user "$(id -u):$(id -g)" \
  -v "$WORK:/work:ro" -v "$CACHE/cargo:/cache" -v "$CACHE/target:/target" \
  -e CARGO_HOME=/cache -e CARGO_TARGET_DIR=/target \
  -e KERNEL_CEDAR_PATCH_SHA256="$PATCH_SHA" \
  -e RUSTFLAGS=-Atext_direction_codepoint_in_literal -w /work "$IMAGE" \
  sh -c 'cargo test --locked --release -j 3 -- --test-threads=1 --nocapture && cargo build --locked --release -j 3'
DEST="$OUT/META-INF/saas-kernel/native/linux-x86_64/libkernel_cedar.so"
cp "$CACHE/target/release/libkernel_cedar.so" "$DEST"
sha256sum "$DEST" | cut -d' ' -f1 > "$DEST.sha256"
printf '%s\n' 'abi=kernel-cedar/1' "cedar.source=$BASE+patch.sha256:$PATCH_SHA" "cedar.patch.sha256=$PATCH_SHA" "toolchain=$IMAGE" "cargo.lock.sha256=$(sha256sum "$HERE/Cargo.lock" | cut -d' ' -f1)" > "$OUT/META-INF/saas-kernel/native/build.properties"
cp "$HERE/Cargo.lock" "$OUT/META-INF/saas-kernel/native/Cargo.lock"
