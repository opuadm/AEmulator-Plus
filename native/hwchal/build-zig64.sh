#!/bin/sh
# hwcomposer.default.so (hwc.c) for the 64-bit firmware of the native arm64 engine, without the NDK: zig cc (LLVM), aarch64-linux-musl.
# Same idea as build-zig.sh (32-bit): the libc imports are resolved against a stand-in libc that carries the LIBC version tag the guest's
# bionic expects; the stand-in is not shipped. Added for AEmulator Plus (2026-11-20), for the refresh-rate / vsync-tick build.
#   sh build-zig64.sh [output]     (default: hwcomposer.default.so next to this script)
# The output is the same file for every n64-<api> layer: vendor#lib64#hw#hwcomposer.default.so
set -eu
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/hwcomposer.default.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
T="--target=aarch64-linux-musl -fPIC -shared -nostdlib -ffreestanding -fno-builtin"
echo 'int hw_get_module(const char *id, const void **m) { (void)id; (void)m; return -1; }' > "$STUB/hw.c"
for f in __system_property_get open ioctl lseek mmap close malloc pthread_create write read socket connect memset memcpy \
         clock_gettime clock_nanosleep; do echo "void $f(void) {}"; done > "$STUB/libc.c"
echo 'LIBC { global: __system_property_get; open; ioctl; lseek; mmap; close; malloc; pthread_create; write; read; socket; connect; memset; memcpy; clock_gettime; clock_nanosleep; };' > "$STUB/libc.map"
$ZIG cc $T -Os -Wl,-soname,libhardware.so -o "$STUB/libhardware.so" "$STUB/hw.c"
$ZIG cc $T -Os -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc_stub.so" "$STUB/libc.c"
$ZIG cc --target=aarch64-linux-musl -fPIC -O2 -fno-builtin -fvisibility=hidden -fno-stack-protector -fno-exceptions -fno-unwind-tables \
  -fno-asynchronous-unwind-tables -c "$HERE/hwc.c" -o "$STUB/hwc.o"
$ZIG cc $T -rtlib=none -Wl,-z,norelro -Wl,--build-id=none -Wl,--no-undefined -Wl,-s -Wl,-soname,hwcomposer.default.so \
  -L"$STUB" -o "$OUT" "$STUB/hwc.o" -lhardware -lc_stub
if readelf -d "$OUT" | grep -q 'libc_stub'; then echo "error: NEEDED libc_stub" >&2; exit 1; fi
ls -l "$OUT"
