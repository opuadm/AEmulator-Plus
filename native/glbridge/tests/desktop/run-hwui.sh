#!/bin/sh
# hwui's dialog (translucent window, nine-patch from a VBO, text from client arrays) on host.c + llvmpipe.   sh run-hwui.sh
set -eu
cd "$(dirname "$0")"
B=${TMPDIR:-/tmp}/aemu-gl-desktop; mkdir -p "$B"
SOCK="$B/gl.sock"; rm -f "$SOCK"
W="-Wall -Wno-unused-function -Wno-unused-variable -Wno-int-conversion"
gcc -O1 -g -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" -I. -I.. -I../.. $W -include hostshim.h -o "$B/hostmain" hostmain.c -lEGL -lGLESv2 -lpthread
gcc -O1 -shared -fPIC -o "$B/libnativewindow.so" fake_nativewindow.c
gcc -O1 -g -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" -I. -I.. -I../.. $W -o "$B/hwui-dialog-e2e" hwui-dialog-e2e.c -ldl -lpthread
RING_DUMP_FILE="$B/ring.txt" LIBGL_ALWAYS_SOFTWARE=1 "$B/hostmain" "$SOCK" > "$B/host.log" 2>&1 < /dev/null &
HOST=$!
trap 'kill $HOST 2>/dev/null || true' EXIT
for i in $(seq 1 25); do [ -S "$SOCK" ] && break; sleep 0.2; done
timeout 240 env LD_LIBRARY_PATH="$B" "$B/hwui-dialog-e2e" || R=1
kill -TERM $HOST 2>/dev/null || true; wait $HOST 2>/dev/null || true
# the call ring (VM settings > Record GL calls): the dump must name the calls and carry a draw's state
grep -q " glBufferSubData " "$B/ring.txt" && grep -q "glDrawArrays .* | err=0 prog=[1-9]" "$B/ring.txt" && echo "call ring: ok ($(wc -l < "$B/ring.txt") lines)" || { echo "call ring: FAILED"; R=1; }
exit ${R:-0}
