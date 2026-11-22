/* Added for AEmulator Plus, 2026-11-19: GPU spoofing on the qemu engines. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File

/**
 * The GL_VENDOR / GL_RENDERER a guest on a qemu engine (Universal 2.2-7.x, Fallback 2.x) is told it has, from the chip the VM
 * reports (see [SocProfiles]). The engine's GL servers (libglserverd_kk, libglserverd_kk_mali, libglserverd_gb) play the guest's
 * GLES calls on the phone's GPU and send the strings of the phone's driver back; native/glid is a library preloaded into
 * them that answers the two strings from the environment instead. Everything else the guest asks (GL_VERSION, extensions,
 * limits) stays the phone's own, so the guest keeps the capabilities it can really use.
 *
 * The native arm64 engine has its own bridge, which takes the strings through [GlHost.identity].
 */
object GlIdentity {
    /** the preload library, in the app's native libraries */
    const val LIB = "libaemuglid.so"
    const val ENV_VENDOR = "AEMU_GL_VENDOR"
    const val ENV_RENDERER = "AEMU_GL_RENDERER"
    private const val PRELOAD = "LD_PRELOAD"

    /** True when the chip names a GPU: at least one of the two strings is set. */
    fun active(vendor: String, renderer: String): Boolean = vendor.isNotBlank() || renderer.isNotBlank()

    /**
     * The environment of a GL server: [base] plus the preload and the two strings. [shim] is the library (null or a file
     * that is not there: the server runs as before and the guest sees the phone's strings). A preload already in [base]
     * (libglbuf) stays, the shim is put in front of it; a blank string is not set, so that one is the phone's.
     */
    fun env(vendor: String, renderer: String, shim: File?, base: Map<String, String>): Map<String, String> {
        if (!active(vendor, renderer) || shim == null || !shim.isFile) return base
        val out = LinkedHashMap(base)
        val have = base[PRELOAD].orEmpty().split(':', ' ').filter { it.isNotEmpty() && it != shim.absolutePath }
        out[PRELOAD] = (listOf(shim.absolutePath) + have).joinToString(":")
        // (printable ASCII on one line already: SocSpec.line; an environment value cannot hold anything else safely)
        if (vendor.isNotBlank()) out[ENV_VENDOR] = vendor else out.remove(ENV_VENDOR)
        if (renderer.isNotBlank()) out[ENV_RENDERER] = renderer else out.remove(ENV_RENDERER)
        return out
    }

    /** True when [env] carries the shim: the server will answer with the VM's strings. */
    fun applied(env: Map<String, String>): Boolean =
        env[PRELOAD].orEmpty().split(':').any { it.endsWith("/$LIB") } && (env.containsKey(ENV_VENDOR) || env.containsKey(ENV_RENDERER))
}
