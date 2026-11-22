#!/bin/sh
# Builds gatekeeper.default.so (both word sizes) of one kernel with zig cc. Added for AEmulator Plus (2026-11-21).
#   pip install ziglang ; sh build-gatekeeper-zig.sh 28 [out-dir]
# Same stub-library trick as build-gralloc-exynos-zig.sh: the libc symbols carry the version name LIBC; the guest's own libc.so
# satisfies the DT_NEEDED entry. Output: vendor#lib64#hw#gatekeeper.default.so and vendor#lib#hw#gatekeeper.default.so.
set -eu
TAG=${1:-28}
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
A=${2:-$HERE/../../app/src/main/assets/engines/n64-$TAG}
cd "$HERE/$TAG"
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'C'
void *calloc(unsigned long n, unsigned long s) { (void)n; (void)s; return 0; }
void free(void *p) { (void)p; }
int open(const char *p, int f, ...) { (void)p; (void)f; return -1; }
long read(int fd, void *b, unsigned long n) { (void)fd; (void)b; (void)n; return -1; }
long write(int fd, const void *b, unsigned long n) { (void)fd; (void)b; (void)n; return -1; }
int close(int fd) { (void)fd; return 0; }
int clock_gettime(int c, void *t) { (void)c; (void)t; return -1; }
void *memcpy(void *d, const void *s, unsigned long n) { (void)s; (void)n; return d; }
void *memset(void *d, int c, unsigned long n) { (void)c; (void)n; return d; }
void __aeabi_memcpy4(void *d, const void *s, unsigned long n) { (void)d; (void)s; (void)n; }
void __aeabi_memcpy(void *d, const void *s, unsigned long n) { (void)d; (void)s; (void)n; }
void __aeabi_memclr4(void *d, unsigned long n) { (void)d; (void)n; }
void __aeabi_memclr8(void *d, unsigned long n) { (void)d; (void)n; }
C
echo 'LIBC { global: calloc; free; open; read; write; close; clock_gettime; memcpy; memset; __aeabi_memcpy4; __aeabi_memcpy; __aeabi_memclr4; __aeabi_memclr8; };' > "$STUB/libc.map"
F="-Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables -Wl,--build-id=none -Wl,-z,now -Wl,-s"
for T in aarch64-linux-musl:lib64 thumb-linux-musleabi:lib; do
  TARGET=${T%%:*}; DIR=${T##*:}
  EX=""; [ "$DIR" = lib ] && EX="-mcpu=cortex_a8 -mfloat-abi=softfp -Wl,--hash-style=both"
  $ZIG cc --target=$TARGET $EX -fPIC -shared -nostdlib -ffreestanding -fno-builtin -Os -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
  $ZIG cc --target=$TARGET $EX $F -Wl,-soname,gatekeeper.default.so -L"$STUB" -o "$A/vendor#$DIR#hw#gatekeeper.default.so" gatekeeper_default.c -l:libc.so
  ls -l "$A/vendor#$DIR#hw#gatekeeper.default.so"
done
