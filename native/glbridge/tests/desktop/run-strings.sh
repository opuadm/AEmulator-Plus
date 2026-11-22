#!/bin/sh
# glGetString with no context current: the host answers from the spoofer's identity (or the phone's real strings), the guest
# falls back to those and never to NULL or to a made-up name.   sh run-strings.sh   (needs what run-desktop.sh needs)
set -eu
cd "$(dirname "$0")"
B=${TMPDIR:-/tmp}/aemu-gl-strings; mkdir -p "$B"
W="-Wall -Wno-unused-function -Wno-unused-variable -Wno-int-conversion"
gcc -O1 -g -D_GNU_SOURCE -I. -I.. -I../.. $W -include hostshim.h -o "$B/hostmain" hostmain.c -lEGL -lGLESv2 -lpthread 2>/dev/null
fail=0
for CASE in "spoofed|Qualcomm|Adreno (TM) 640" "real||"; do
  NAME=${CASE%%|*}; REST=${CASE#*|}; V=${REST%%|*}; R=${REST#*|}
  SOCK="$B/gl-$NAME.sock"; rm -f "$SOCK"
  gcc -O1 -g -D_GNU_SOURCE "-DAEMU_GL_SOCKET=\"$SOCK\"" -I. -I.. -I../.. $W -o "$B/strings-e2e" strings-nocontext-e2e.c -ldl -lpthread 2>/dev/null
  AEMU_TEST_VENDOR="$V" AEMU_TEST_RENDERER="$R" LIBGL_ALWAYS_SOFTWARE=1 "$B/hostmain" "$SOCK" > "$B/host-$NAME.log" 2>&1 < /dev/null &
  HOST=$!
  for i in $(seq 1 25); do [ -S "$SOCK" ] && break; sleep 0.2; done
  echo "== $NAME identity"
  timeout 60 "$B/strings-e2e" "$V" "$R" || fail=1
  kill $HOST 2>/dev/null || true; wait $HOST 2>/dev/null || true
done
exit $fail
