#!/bin/sh
# Same as build.sh (libaemu64_<tag>.so only) but with zig cc instead of the NDK. Added for AEmulator Plus (2026-11-21).
#   pip install ziglang ; sh build-zig.sh <tag> [outdir]
set -eu
TAG=$1
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=${2:-$HERE/../../app/src/main/jniLibs/arm64-v8a}
mkdir -p "$DIR"
cd "$HERE/$TAG"
$ZIG cc --target=aarch64-linux-musl -O2 -static -nostdlib -ffreestanding -fno-builtin -fno-stack-protector \
  -fno-pic -no-pie -Wall -Wno-unused-function -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,--image-base=0x2000000000 -Wl,--build-id=none -Wl,-z,norelro -Wl,-s \
  -o "$DIR/libaemu64_$TAG.so" start.S run64.c
ls -l "$DIR/libaemu64_$TAG.so"
