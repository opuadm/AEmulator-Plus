#!/bin/sh
# glid without the NDK: zig cc (LLVM), aarch64-linux-musl. The library is freestanding apart from two imports, getenv (libc.so)
# and dlsym (libdl.so), which come from throw-away stub libraries of those names; at run time the phone's bionic provides them.
# Main build path: build.sh with the NDK.
#   sh build-zig.sh [output]
set -eu
ZIG=${ZIG:-python3 -m ziglang}
cd "$(dirname "$0")"
OUT=${1:-../../app/src/main/jniLibs/arm64-v8a/libaemuglid.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
T="--target=aarch64-linux-musl"
echo 'char *getenv(const char *n) { (void)n; return 0; }' > "$STUB/libc.c"
echo 'void *dlsym(void *h, const char *n) { (void)h; (void)n; return 0; }' > "$STUB/libdl.c"
$ZIG cc $T -fPIC -shared -nostdlib -Wl,-soname,libc.so  -o "$STUB/libc.so"  "$STUB/libc.c"
$ZIG cc $T -fPIC -shared -nostdlib -Wl,-soname,libdl.so -o "$STUB/libdl.so" "$STUB/libdl.c"
$ZIG cc $T -Os -fPIC -shared -nostdlib -ffreestanding -fvisibility=hidden -fno-stack-protector -Wall \
    -fno-unwind-tables -fno-asynchronous-unwind-tables \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,--no-undefined -Wl,-s -Wl,-soname,libaemuglid.so \
    -Wl,--no-as-needed -o "$OUT" glid.c "$STUB/libdl.so" "$STUB/libc.so"
ls -l "$OUT"
