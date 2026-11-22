#!/bin/sh
# The host renderer libaemugl.so (host.c) without the NDK: zig cc (LLVM), aarch64-linux-musl, the same idea as build64-zig.sh:
# the libc imports resolve against a stand-in libc.so that carries the LIBC version tag bionic's linker expects (it is not
# shipped), errno is bionic's __errno(), thread-locals run on pthread keys (emutls.c), and libEGL / libGLESv3 / liblog /
# libnativewindow / libandroid are empty stand-ins that only put their names into DT_NEEDED (their symbols are the phone's).
# Needs the Khronos EGL / GLES headers (apt: libegl-dev libgles-dev) and jni.h (tests/desktop).
#   sh build-host-zig.sh [output]     (default: ../../app/src/main/jniLibs/arm64-v8a/libaemugl.so)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
cd "$(dirname "$0")"
OUT=${1:-../../app/src/main/jniLibs/arm64-v8a/libaemugl.so}
KHR=${KHR_INC:-/usr/include}
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/inc/android" "$W/khr"
for d in EGL GLES GLES2 GLES3 KHR; do [ -d "$KHR/$d" ] && ln -s "$KHR/$d" "$W/khr/$d"; done
cat > "$W/inc/android/log.h" <<'EOT'
#pragma once
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
int __android_log_print(int prio, const char *tag, const char *fmt, ...);
EOT
cat > "$W/inc/android/hardware_buffer.h" <<'EOT'
#pragma once
#include <stdint.h>
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct { uint32_t width, height, layers, format; uint64_t usage; uint32_t stride, rfu0; uint64_t rfu1; } AHardwareBuffer_Desc;
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_USAGE_CPU_READ_RARELY 2ull
#define AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY 0x20ull
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE 0x100ull
#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT 0x200ull
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *d, AHardwareBuffer **o);
void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *d);
int AHardwareBuffer_lock(AHardwareBuffer *b, uint64_t u, int32_t f, const void *r, void **v);
int AHardwareBuffer_unlock(AHardwareBuffer *b, int32_t *f);
void AHardwareBuffer_release(AHardwareBuffer *b);
EOT
cat > "$W/inc/android/native_window_jni.h" <<'EOT'
#pragma once
#include <jni.h>
typedef struct ANativeWindow ANativeWindow;
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *e, jobject s);
void ANativeWindow_release(ANativeWindow *w);
EOT
LIBC="__cxa_finalize __cxa_atexit accept4 access bind calloc clock_gettime close free getsockopt listen malloc memcpy memmove memset pthread_attr_destroy pthread_attr_init pthread_attr_setdetachstate pthread_cond_broadcast pthread_cond_timedwait pthread_create pthread_detach pthread_mutex_lock pthread_mutex_unlock pthread_once pthread_setspecific pthread_getspecific pthread_key_create read realloc send setsockopt shutdown snprintf socket strcat strchr strdup strerror strlen strncmp strncpy strnlen strstr unlink abort"
{ echo "#define S(n) void n(void) {}"; for s in $LIBC; do echo "S($s)"; done; echo 'int *__errno(void) { return 0; }'; } > "$W/libc.c"
{ printf 'LIBC { global:'; for s in $LIBC; do printf ' %s;' "$s"; done; echo ' __errno; };'; } > "$W/libc.map"
echo 'extern int *__errno(void); int *__errno_location(void) { return __errno(); }' > "$W/errno.c"
T="--target=aarch64-linux-musl -fPIC -shared -nostdlib"
$ZIG cc $T -Os -fno-builtin -Wl,--version-script,"$W/libc.map" -Wl,-soname,libc.so -o "$W/libc_stub.so" "$W/libc.c"
for n in EGL GLESv3 log nativewindow android; do echo "" > "$W/s_$n.c"; $ZIG cc $T -Os -Wl,-soname,lib$n.so -o "$W/lib${n}_stub.so" "$W/s_$n.c"; done
$ZIG cc --target=aarch64-linux-musl -DANDROID -fPIC -shared -O2 -fvisibility=hidden -mno-outline-atomics \
  -I "$W/inc" -I tests/desktop -I "$W/khr" -I . \
  -Wall -Wno-unused-function -fno-stack-protector -femulated-tls -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,lazy -Wl,-z,norelro -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,--hash-style=both -Wl,-s -Wl,-soname,libaemugl.so \
  -L"$W" -o "$W/out.so" host.c "$W/errno.c" emutls.c \
  -Wl,--no-as-needed -lc_stub -lEGL_stub -lGLESv3_stub -llog_stub -lnativewindow_stub -landroid_stub
cp "$W/out.so" "$OUT"; ls -l "$OUT"
