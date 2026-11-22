#!/bin/sh
# Host test of glid.c (x86-64, gcc): the interposer in front of a stand-in driver, called through the PLT like glserverd does.
set -eu
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
T=$(cd "$(dirname "$0")" && pwd)
CC=${CC:-gcc}
$CC -fPIC -shared -o "$D/libfakegl.so" "$T/fakegl.c"
$CC -fPIC -shared -fvisibility=hidden -o "$D/libaemuglid.so" "$T/../glid.c" -ldl
$CC -o "$D/server" "$T/server.c" -L"$D" -lfakegl -Wl,-rpath,"$D"
export LD_LIBRARY_PATH="$D"
P="$D/libaemuglid.so"
# no preload: the driver's own strings
"$D/server" "Qualcomm" "Adreno (TM) 740"
# preloaded, nothing set: the driver's own strings
env LD_PRELOAD="$P" "$D/server" "Qualcomm" "Adreno (TM) 740"
# both set
env LD_PRELOAD="$P" AEMU_GL_VENDOR="ARM" AEMU_GL_RENDERER="Mali-T760" "$D/server" "ARM" "Mali-T760"
# one set, one blank
env LD_PRELOAD="$P" AEMU_GL_RENDERER="NVIDIA Tegra K1" AEMU_GL_VENDOR="" "$D/server" "Qualcomm" "NVIDIA Tegra K1"
# the shim composes with another preload (libglbuf in the app)
env LD_PRELOAD="$P:$P" AEMU_GL_VENDOR="Intel" AEMU_GL_RENDERER="Mesa DRI Intel(R) HD Graphics" "$D/server" "Intel" "Mesa DRI Intel(R) HD Graphics"
echo "glid: all ok"
