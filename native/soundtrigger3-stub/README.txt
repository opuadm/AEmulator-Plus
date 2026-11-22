android.hardware.soundtrigger3 V4 - arm64 build from aosp-mirror-neo @ android-17.0.0_r1
Built with zig 0.17.1 (ld.lld) + AOSP aidl compiler built from source. NOT run on a device.

lib64/  -> /vendor/lib64/   (soundtrigger3-V4-ndk, soundtrigger.types-V4-ndk, audio.common.types-V5-ndk)
bin/android.hardware.soundtrigger3-service.stub -> /vendor/bin/hw/  (chmod 755)
etc/init/*.rc           -> /vendor/etc/init/
etc/vintf/manifest/*.xml-> /vendor/etc/vintf/manifest/

IMPORTANT
- AOSP android-17.0.0_r1 contains ONLY the soundtrigger3 AIDL interface; it has no service, .rc or VINTF
  fragment. The service, .rc and manifest here are written by me, not AOSP. The service is a stub: every
  call returns OPERATION_NOT_SUPPORTED. Replace with a real HAL for working hardware.
- Linked against empty stub libs for libbinder_ndk/libc++/libc; real ones resolve on-device.
- Compiled with zig's libc++ headers (std::__1), not AOSP's; ABI mismatch possible.
- Outline atomics replaced by a small shim (__aarch64_ldadd8_*).
- The mirror has no system/core (libcutils, libutils) so libbinder itself was not built.
- SELinux: needs a domain/file_contexts/hwservice-style service_contexts entry for
  android.hardware.soundtrigger3.ISoundTriggerHw/default, otherwise it will be denied.

2026-11-19 FIX (AEmulator Plus): the zig-built atomics shim (__aarch64_ldadd8_relax / _acq_rel) called itself, so the
service died on its first atomic add (SIGSEGV, exit 139, endless recursion). patch_atomics.py rewrites the shim in the
four binaries with a real ldaxr/stlxr loop that only clobbers x16/x17. Rebuild properly with -mno-outline-atomics or an
assembly shim, and the patch is not needed.
