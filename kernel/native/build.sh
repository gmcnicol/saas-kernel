#!/usr/bin/env bash
# Build native resources inside the existing runtime artifact; never install a host toolchain.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$1
CACHE=${KERNEL_NATIVE_CACHE:-"$HERE/../target/native-cache"}
IMAGE=rust@sha256:3914072ca0c3b8aad871db9169a651ccfce30cf58303e5d6f2db16d1d8a7e58f
mkdir -p "$CACHE/cargo" "$CACHE/target" "$OUT/META-INF/saas-kernel/native/linux-x86_64"
docker run --rm --platform linux/amd64 --cpus=3 --user "$(id -u):$(id -g)" \
  -v "$HERE:/work:ro" -v "$CACHE/cargo:/cache" -v "$CACHE/target:/target" \
  -e CARGO_HOME=/cache -e CARGO_TARGET_DIR=/target \
  -e RUSTFLAGS=-Atext_direction_codepoint_in_literal -w /work "$IMAGE" \
  sh -c 'cargo test --locked --release -j 3 -- --test-threads=1 && cargo build --locked --release -j 3'
DEST="$OUT/META-INF/saas-kernel/native/linux-x86_64/libkernel_cedar.so"
cp "$CACHE/target/release/libkernel_cedar.so" "$DEST"
sha256sum "$DEST" | cut -d' ' -f1 > "$DEST.sha256"
printf '%s\n' 'abi=kernel-cedar/1' 'cedar.source=09e21d967f1da2cf2abbe1e0b0b2067b41867735' "toolchain=$IMAGE" "cargo.lock.sha256=$(sha256sum "$HERE/Cargo.lock" | cut -d' ' -f1)" > "$OUT/META-INF/saas-kernel/native/build.properties"
cp "$HERE/Cargo.lock" "$OUT/META-INF/saas-kernel/native/Cargo.lock"
