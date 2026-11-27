# Open GL bridge for the 32-bit engines (2026-11-25)

ARM firmware of every released Android, 1.0 to 7.x, on the **KK** engine (`libqemu_kk`) and the **GB** engine (`libqemu_gb`) now draws through the open
bridge (`native/glbridge`: `libGLES_aemu.so` in the guest, `GlHost`/`libaemugl.so` in the app) instead of the engine's closed pair
(`libGLES.so` / `libGLES_dhd.so` + `libglserverd_*`). The native arm64 engine and x86 firmware already did.
`OpenGl.kt` decides; a file `glclosed` in the image's directory puts one VM back on the closed driver.

## What the bridge is in this mode

"Solo": no SwiftShader behind it, so the driver is the whole EGL (display, configs, `eglQueryString`, contexts, surfaces). Every GLES 2/3
context is a host context; a GLES 1 context (SurfaceFlinger of 2.x, boot animation, wallpapers, old games) runs on `gles1.h`, the fixed-function
pipeline written in GLSL over the same encoders. `eglSwapBuffers` reads the frame back into the window's buffer (`OP_SWAP_FMT`, the host does the
pixel conversion), the guest's gralloc / framebuffer HAL then puts the buffer on fb0 as with software rendering. No glserverd process, no fb0
writer of its own, no `libglbuf` preload; `GlHost.identity` renames the GPU.

## AOSP analysis (what was read, what it means)

Headers and sources were read from the AOSP mirrors for 1.6, 2.0, 2.1, 2.2.3, 2.3.7, 4.0.4, 4.1.2, 4.2.2, 4.3, 4.4.4, 5.0, 6.0 and 7.1.

| Finding | Source | Consequence |
| --- | --- | --- |
| 2.0 - 4.1: `ANativeWindow` has `setSwapInterval, dequeueBuffer(w,&b), lockBuffer, queueBuffer, query, perform` (+ `cancelBuffer` from 2.3, then `reserved_proc[2]`); no fences. 4.2+ keeps those six as `*_DEPRECATED` and **adds** `dequeueBuffer/queueBuffer/cancelBuffer` with a fence after `cancelBuffer_DEPRECATED`. The first seven slots are at the same offsets in every release. | `android_natives.h` (2.0/2.1/2.2/2.3), `system/window.h` (4.0.4 ... 7.1) | **Bug in the old driver:** `guest.c` always called the 4.2+ slots; on 2.x - 4.1 that slot is `reserved_proc` = NULL, a call through NULL in SurfaceFlinger. Fixed: `win_new_abi()` reads `common.version` (= `sizeof` of the window the release was built with: 100 bytes before 4.2, 104 from 4.2, 32-bit) and `w_dequeue / w_queue / w_cancel` use the right slots. |
| Before 4.2 a dequeued buffer must be locked (`lockBuffer`) before it is drawn into | `opengl/libagl/egl.cpp` 2.0, 2.2, 2.3, 4.0.4: `set_usage, dequeue, lockBuffer, draw, queue, dequeue, lockBuffer` | `w_dequeue` calls `lockBuffer` on the old table. |
| AOSP's own EGL (libagl) never calls `NATIVE_WINDOW_CONNECT` before 4.1 and asks for `SW_READ_OFTEN \| SW_WRITE_OFTEN` only | same files | no connect added (it would diverge from the reference); usage 0x33 before 4.2 (0x933 as before from 4.2): `HW_RENDER`/`HW_COMPOSER` would send a vendor gralloc to pmem / GPU memory the bridge cannot fill (the same trap as in `native/gralloc-eclair`). |
| 2.x `libEGL` (`Loader.cpp`, `egl.cpp`) has two slots: `egl.cfg` line `0 0 tag` is the software impl it insists on (`LOG_FATAL ... default OpenGL ES implementation`), `0 1 tag` the hardware one; configs of both are merged | 2.3.7 `opengl/libs/EGL/Loader.cpp`, `egl.cpp` | up to 4.0.4 egl.cfg is the single line `0 0 aemu` (as `0 0 dhd` was), so no libagl config can be picked for a program; 4.1+ keep `0 0 android` / `0 1 aemu`. `load_driver("GLES", tag)` needs `eglGetProcAddress` and the EGL names: exported by `guest.c`. |
| 1.0 - 1.6: the window is an `egl_native_window_t` (magic `0x600913`, the first word of both kinds; 2.0 already has `android_natives.h`): a mapped buffer (`base + offset`, `stride` in pixels, `format`, `memory_type` 2 = framebuffer) and `swapBuffers(window)`. `libEGL` has incRef'ed and `connect()`ed it before the driver sees it; libagl draws into `base + offset` and calls `swapBuffers`, which posts a Surface or flips the display window's pages | 1.6 `eglnatives.h`, `EGLNativeWindowSurface.cpp`, `libagl/egl.cpp`, `libs/EGL/egl.cpp` | `guest.c` recognises the magic (`eglCreateWindowSurface`) and `bridge_swap_egl()` reads the host's frame back into `base + offset` in the window's own format (fb0's real layout for `memory_type` FB), then calls `swapBuffers`. |
| 1.x `libEGL` has no `egl.cfg`: it `dlopen`s `libagl.so` (software impl) and `libhgl.so` (hardware impl; asks SurfaceFlinger for the GPU with `requestGPU`, context-lost handling for other processes) | 1.6 `libs/EGL/egl.cpp` `load_driver` | the driver takes the place of **`libagl.so`** (the original is kept as `libagl.so.aemu-sw` and put back when the bridge is off); `libhgl.so` is left alone so the `requestGPU` machinery never starts. |
| 1.0 / 1.1 page flipping: the display window is two pages of fb0 and libagl stops flipping without a second page | `LegacyFirmware.API1_TRANSLATOR` (`yres_virtual = 2 * yres`) | the existing translator patch is exactly what the bridge needs too: it writes the back page and `swapBuffers` flips. Nothing new to patch. |
| Imports of the armv7 build are `libc/libdl/liblog` only, no `INIT_ARRAY`, TLS emulated, `getprogname` weak, `HASH` present | `readelf` of the rebuilt asset | nothing a 2.3 linker (eager relocation, no GNU hash) cannot resolve. |
| `meta_note_at()` is guarded by the handle's shape (12, 1 fd, magic) | `native/grallocwrap/note.h` | a stock gralloc's buffers are read back, never taken for the engine's note. |

## Not verified (no device, no frameworks/native mirror reachable in this session)

* **1.0, 1.1 and 1.5 sources are not in the mirror** (frameworks/base has tags from 1.6): `egl_native_window_t`'s leading fields (everything up to `swapBuffers`, which is what the driver touches) are taken from 1.6; if 1.0 differs, the first frames are wrong or the guest crashes in `bridge_swap_egl` (it logs the window it saw, three times: `1.x window WxH stride .. format ..`).
* The bionic of 1.0 was not available either: the driver's imports are `libc` / `libdl` / `liblog` only, `poll`, `prctl` and `getprogname` are weak, but an import 1.0's libc lacks would stop `libagl.so` from loading (eager relocation) and `libEGL` would find no driver. `/dev/socket/aemu_gl` is reached through the translator's path mapping as on 2.x; a 1.x `init` that does not create `/dev/socket` was not checked.
* Pre-1.0 builds (release 0.9) keep their software renderer.
* Nothing here was run: the Kotlin was not compiled (no Gradle/SDK), the guest driver was built (`build32-zig.sh`, `build-x86-zig.sh i386`) but not loaded in a guest.
* `BufferQueue` connect rules of 4.1 - 4.4 (`frameworks/native`) could not be fetched. The 5.0+ path has worked without `connect` so far; 4.1 - 4.4 are unconfirmed.
* Vendor gralloc modules of 2.x - 4.x firmware (the engine's own `gralloc.default.so` is installed over them by `installEngineFiles`): the bridge only needs `lock`/`unlock`, but a gralloc that cannot lock a buffer with `SW_WRITE_OFTEN` makes `aemu-gl: a frame is dropped ...` appear in the log.
* GB engine: fb0 page flipping with the engine's gralloc was not exercised.
* hwui stays as the VM's setting says. With every context on the host the old reason for turning it off (the closed bridge mis-carried hwui's stream) is gone, but it should be tried per ROM.
* The arm64 / x86-64 builds of the driver were not rebuilt (their window code is the 4.2+ path, unchanged); the sources and these two binaries are in sync, the audit's hashes are not updated.

## Still closed (kept as the fallback behind `glclosed`)

`libglserverd_gb/kk/kk_mali.so`, `libGLES_dhd.so`, `engines/kk/libGLES.so`, `libGLES_split.so`, `libGLES_route.so`, `libglbuf.so`, `libglbridge.so`
(in-app closed renderer). They are no longer started or installed for 2.0+ ARM guests unless `glclosed` exists, or for Android 1.0 - 1.6.

## x86 firmware on the KK engine (x86-kk)

It already used the open i386 bridge (`EngineAssets.kkX86OpenBridge`, no API gate; the code comment says Android 1-7, the oldest x86 port the docs mention is Eclair, API 5).
Both changes of this note reach it through the same `guest.c`: the pre-4.2 window table (x86 Eclair - JB 4.1) and the rebuilt
`engines/common-x86_64/system#lib#egl#libGLES_aemu.so`. Changed for x86 as well: `egl.cfg` up to 4.0.4 is the single line `0 0 aemu` (it was
`0 0 android` / `0 1 aemu`, which lets the 2.x EGL merge libagl's configs with ours). Not changed: no x86 1.x exists (no `libagl.so` step), and
nothing about the x86 gralloc / fb0 handling (docs/x86-guest.md). Unverified like the rest: no x86 guest was run.

## GL_TEXTURE_EXTERNAL_OES in the GLES 1 emulation (2026-11-25)

`gles1.h` knew only `GL_TEXTURE_2D`. SurfaceFlinger of 4.x draws every layer through `GL_TEXTURE_EXTERNAL_OES`
(enable, bind, draw, disable): the enable went to the host as a capability (`GL error 0x0500` in SurfaceFlinger's log) and the
layers were drawn without a texture (white screen). Now an external target is tracked per texture unit (enable / bind /
texparam / query), wins over 2D when both are on, and the generated fragment shader samples a `samplerExternalOES` (the host
turns it into a 2D sampler and binds the external texture, `no_external` / `fix_units`). `GL_OES_EGL_image_external` is
advertised. Covered by `scene_external` in `tests/desktop/gles1-e2e.c`. Rebuilt: `engines/kk/libGLES_aemu.so` (ARM32) and the
i386 driver (zig 0.17.1 / clang 22.1.8 reproduces the previous binaries byte for byte). The arm64 / x86-64 drivers were not rebuilt.
