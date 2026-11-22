package app.aemu.core

/**
 * The renderer of guest programs on the phone's GPU (native/glbridge/host.c): the guest's GLES driver connects to
 * the socket, one connection per drawing thread, and its commands are played in real GLES 3 contexts here.
 */
object GlHost {
    /** Added for AEmulator Plus (2026-11-19): why libaemugl.so did not load (blank when it did); the log used to say only "does not load" */
    @Volatile var loadError: String = ""
        private set
    private val ok: Boolean = try { System.loadLibrary("aemugl"); true } catch (t: Throwable) { loadError = t.toString(); false }

    /** True when libaemugl.so is loaded in this process (the GPU bridge can run at all) */
    val loaded: Boolean get() = ok

    /**
     * Added for AEmulator Plus (2026-11-19). The GL_VENDOR / GL_RENDERER strings the guest's glGetString answers while the
     * phone's GPU renders (see [SocProfiles]); blank = the phone's real ones. Call before [start]. Returns false when
     * libaemugl.so predates this call (the guest then sees the real strings).
     */
    fun identity(vendor: String, renderer: String): Boolean =
        ok && runCatching { nativeIdentity(vendor, renderer); true }.getOrDefault(false)

    /** "ok <renderer>, <version>" or why not */
    fun start(socket: String): String = if (ok) runCatching { nativeStart(socket) }.getOrElse { it.toString() } else "libaemugl.so does not load: ${loadError.ifBlank { "unknown error" }}"
    fun stop() { if (ok) runCatching { nativeStop() } }

    /**
     * The call ring (VM settings > Record GL calls): on keeps the host renderer's last 16k calls in memory for [traceDump].
     * Returns false when libaemugl.so predates it. Set before [start].
     */
    fun trace(on: Boolean): Boolean = ok && runCatching { nativeTrace(on); true }.getOrDefault(false)
    /** the ring as text (oldest first), or null when libaemugl.so does not have it */
    fun traceDump(): String? = if (ok) runCatching { nativeTraceDump() }.getOrNull() else null

    /** the view finished frames are drawn into by the GPU (null: none, frames are dropped) */
    fun surface(s: android.view.Surface?) { if (ok) runCatching { nativeSurface(s) } }
    /** how many frames went to the screen that way since start() */
    fun presented(): Long = if (ok) runCatching { nativePresented() }.getOrDefault(0L) else 0L

    @JvmStatic private external fun nativeStart(socket: String): String
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeIdentity(vendor: String, renderer: String)
    @JvmStatic private external fun nativeSurface(s: android.view.Surface?)
    @JvmStatic private external fun nativePresented(): Long
    @JvmStatic private external fun nativeTrace(on: Boolean)
    @JvmStatic private external fun nativeTraceDump(): String
}
