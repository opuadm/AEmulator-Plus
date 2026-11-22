#!/bin/sh
# glid: GL_VENDOR / GL_RENDERER the qemu engines' GL servers report, preloaded into libglserverd_* (arm64, app's native libs)
NDK=${NDK:-$ANDROID_NDK_HOME}
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$BIN" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$(dirname "$0")" || exit 1
OUT=${1:-../../app/src/main/jniLibs/arm64-v8a/libaemuglid.so}
"$BIN/clang" --target=aarch64-linux-android21 -O2 -fPIC -shared -fvisibility=hidden -fno-stack-protector -Wall \
  -Wl,-z,max-page-size=16384 -Wl,-soname,libaemuglid.so -o "$OUT" glid.c -ldl || exit 1
"$BIN/llvm-strip" "$OUT"
ls -la "$OUT"
