#!/bin/sh
# The bridge end to end on this machine: host.c (the phone's side) on Mesa's llvmpipe, guest.c (the guest's driver) as a
# test client that draws what Android's boot animation and old games draw, and checks the pixels that come back.
#   sh run-desktop.sh            needs: gcc, libegl-dev, libgles-dev, libegl-mesa0, libgl1-mesa-dri
set -eu
cd "$(dirname "$0")"
B=${TMPDIR:-/tmp}/aemu-gl-desktop; mkdir -p "$B"
SOCK="$B/gl.sock"; rm -f "$SOCK"
W="-Wall -Wno-unused-function -Wno-unused-variable -Wno-int-conversion"
# the host: the real host.c, Android's headers stood in for
gcc -O1 -g -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" -I. -I.. -I../.. $W -include hostshim.h -o "$B/hostmain" hostmain.c -lEGL -lGLESv2 -lpthread
# the guest: guest.c included whole, a libnativewindow that locks the fake window's memory
#   M32=1: the guest as an i386 program under qemu-user, which is what an x86 firmware's driver is (needs qemu-user, gcc-multilib)
if [ -n "${M32:-}" ]; then ARCH=-m32; RUN="qemu-i386 -L / -E LD_LIBRARY_PATH=$B"; else ARCH=; RUN="env LD_LIBRARY_PATH=$B"; fi
gcc $ARCH -O1 -shared -fPIC -o "$B/libnativewindow.so" fake_nativewindow.c
gcc $ARCH -O1 -g -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" -I. -I.. -I../.. $W -o "$B/gles1-e2e" gles1-e2e.c -ldl -lpthread
LIBGL_ALWAYS_SOFTWARE=1 "$B/hostmain" "$SOCK" > "$B/host.log" 2>&1 < /dev/null &
HOST=$!
trap 'kill $HOST 2>/dev/null || true' EXIT
for i in $(seq 1 25); do [ -S "$SOCK" ] && break; sleep 0.2; done
timeout 240 $RUN "$B/gles1-e2e"
