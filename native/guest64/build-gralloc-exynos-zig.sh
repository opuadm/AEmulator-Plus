#!/bin/sh
# Builds libgralloc_exynos.so (both word sizes) of one kernel with zig cc instead of the NDK. Added for AEmulator Plus (2026-11-21).
#   pip install ziglang ; sh build-gralloc-exynos-zig.sh 28
# Same output as the exynos block of build.sh. The libc/libdl symbols come from small stub libraries that carry the
# version name LIBC (a guest's bionic names its imports that way: getpid@LIBC); the stubs are not shipped, the guest's own
# libc.so / libdl.so satisfy the DT_NEEDED entries.
set -eu
TAG=${1:-28}
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
A=${2:-$HERE/../../app/src/main/assets/engines/n64-$TAG}
cd "$HERE/$TAG"
P=system; grep -q '"/vendor/lib64/libgralloc_rom.so"' gralloc_exynos.c && P=vendor
STUB=$(mktemp -d)
trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'C'
int getpid(void) { return 0; }
void *calloc(unsigned long n, unsigned long s) { (void)n; (void)s; return 0; }
void free(void *p) { (void)p; }
void *memcpy(void *d, const void *s, unsigned long n) { (void)s; (void)n; return d; }
void *memset(void *d, int c, unsigned long n) { (void)c; (void)n; return d; }
void __aeabi_memcpy4(void *d, const void *s, unsigned long n) { (void)d; (void)s; (void)n; }
void __aeabi_memcpy(void *d, const void *s, unsigned long n) { (void)d; (void)s; (void)n; }
void __aeabi_memclr4(void *d, unsigned long n) { (void)d; (void)n; }
void __aeabi_memclr8(void *d, unsigned long n) { (void)d; (void)n; }
C
cat > "$STUB/libdl.c" <<'C'
void *dlopen(const char *n, int f) { (void)n; (void)f; return 0; }
void *dlsym(void *h, const char *n) { (void)h; (void)n; return 0; }
C
echo 'LIBC { global: getpid; calloc; free; memcpy; memset; __aeabi_memcpy4; __aeabi_memcpy; __aeabi_memclr4; __aeabi_memclr8; };' > "$STUB/libc.map"
echo 'LIBC { global: dlopen; dlsym; };' > "$STUB/libdl.map"
F="-Os -fPIC -shared -nostdlib -ffreestanding -fno-builtin -fvisibility=hidden -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables -Wl,--build-id=none -Wl,-z,now -Wl,-s"
for T in aarch64-linux-musl:lib64 thumb-linux-musleabi:lib; do
  TARGET=${T%%:*}; DIR=${T##*:}
  EX=""; [ "$DIR" = lib ] && EX="-mcpu=cortex_a8 -mfloat-abi=softfp -Wl,--hash-style=both"
  $ZIG cc --target=$TARGET $EX -fPIC -shared -nostdlib -ffreestanding -fno-builtin -Os -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
  $ZIG cc --target=$TARGET $EX -fPIC -shared -nostdlib -ffreestanding -fno-builtin -Os -Wl,--version-script,"$STUB/libdl.map" -Wl,-soname,libdl.so -o "$STUB/libdl.so" "$STUB/libdl.c"
  $ZIG cc --target=$TARGET $EX $F -Wl,-soname,gralloc.default.so -L"$STUB" -o "$A/$P#$DIR#libgralloc_exynos.so" gralloc_exynos.c -l:libdl.so -l:libc.so
  ls -l "$A/$P#$DIR#libgralloc_exynos.so"
done
