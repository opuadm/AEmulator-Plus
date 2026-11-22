/*
 * glid: GL_VENDOR / GL_RENDERER of the guest on the qemu engines.
 * Added for AEmulator Plus, 2026-11-19. GPL-3.0; see LICENSE and NOTICE.md.
 *
 * Preloaded into the engine's GL servers (libglserverd_kk, libglserverd_kk_mali, libglserverd_gb, started by GuestVm.glUp).
 * Those servers play the guest's GLES calls on the phone's GPU and send the answer of glGetString back to the guest, so the
 * guest's GLES driver (which has no strings of its own) reports whatever the phone's driver says: "Adreno (TM) 740" in a
 * VM that is set to be a Mali-T760 phone. The servers import glGetString from libGLESv1_CM / libGLESv2 (undefined dynamic
 * symbol, resolved through the PLT), so a preloaded library that exports the same name is what they call. It answers the
 * two strings from the environment and hands every other question, and these two when the variable is unset or empty, to
 * the next definition (the phone's driver). GL_VERSION, GL_EXTENSIONS and the rest are the phone's own: the guest still
 * gets the capabilities it can really use, only the name of the chip it is told it has changes.
 *
 *   AEMU_GL_VENDOR    the answer to glGetString(GL_VENDOR)     (blank: the phone's)
 *   AEMU_GL_RENDERER  the answer to glGetString(GL_RENDERER)   (blank: the phone's)
 *
 * build: sh build.sh (NDK) or sh build-zig.sh (zig cc, no NDK)  ->  app/src/main/jniLibs/arm64-v8a/libaemuglid.so
 */
/* (no headers: the two imports are declared here, so the same file builds with the NDK and with zig cc without a libc) */
extern char *getenv(const char *name);
extern void *dlsym(void *handle, const char *symbol);

#define EXPORT __attribute__((visibility("default")))

#define GL_VENDOR   0x1F00
#define GL_RENDERER 0x1F01

/* RTLD_NEXT of every 64-bit libc (bionic, glibc, musl); the library is built for arm64 only */
#define NEXT ((void *)-1L)

typedef const unsigned char *(*get_string_fn)(unsigned int);

EXPORT const unsigned char *glGetString(unsigned int name) {
    static get_string_fn real;
    if (name == GL_VENDOR || name == GL_RENDERER) {
        const char *v = getenv(name == GL_VENDOR ? "AEMU_GL_VENDOR" : "AEMU_GL_RENDERER");
        if (v && *v) return (const unsigned char *)v;
    }
    if (!real) real = (get_string_fn)dlsym(NEXT, "glGetString");
    return real ? real(name) : 0;
}
