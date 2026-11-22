#!/bin/sh
# Benchmark of the guest driver's CPU cost, as an i386 program under qemu-user (the way an x86 firmware runs), against the
# desktop host (host.c on llvmpipe). sh run-bench.sh [path of a guest.c to measure; default: the one in this tree]
#   needs: qemu-user, gcc-multilib, libc6-i386, and what run-desktop.sh needs
set -eu
cd "$(dirname "$0")"
GUEST=${1:-../../guest.c}
GUEST=$(cd "$(dirname "$GUEST")" && pwd)/$(basename "$GUEST")
B=${TMPDIR:-/tmp}/aemu-gl-bench; mkdir -p "$B"
SOCK="$B/gl.sock"; rm -f "$SOCK"
W="-Wall -Wno-unused-function -Wno-unused-variable -Wno-int-conversion"
gcc -O2 -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" -I. -I.. -I../.. -I"$(dirname "$GUEST")" $W -include hostshim.h -o "$B/hostmain" hostmain.c -lEGL -lGLESv2 -lpthread 2>/dev/null
# EMUTLS=old|new: thread-locals as the zig build of an x86 firmware has them (emulated, on pthread keys); unset: the compiler's own
TLS=""; case "${EMUTLS:-}" in old) TLS="-femulated-tls emutls_old.c"; CC32=clang ;; new) TLS="-femulated-tls ../../emutls.c"; CC32=clang ;; esac
${CC32:-gcc} -m32 -O2 -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" "-DGUEST_C=\"$GUEST\"" -I"$(dirname "$GUEST")" $W -o "$B/swap-bench" swap-bench.c $TLS -ldl -lpthread
gcc -m32 -O1 -shared -fPIC -o "$B/libnativewindow.so" fake_nativewindow.c
LIBGL_ALWAYS_SOFTWARE=1 "$B/hostmain" "$SOCK" > "$B/host.log" 2>&1 < /dev/null &
HOST=$!
trap 'kill $HOST 2>/dev/null || true' EXIT
for i in $(seq 1 25); do [ -S "$SOCK" ] && break; sleep 0.2; done
timeout 250 qemu-i386 -L / -E LD_LIBRARY_PATH="$B"  "$B/swap-bench"
