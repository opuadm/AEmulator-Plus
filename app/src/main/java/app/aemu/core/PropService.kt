package app.aemu.core

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Замена property_service из init: принимает setprop от гостя и пишет в общую область,
 * которую все гостевые процессы отображают через fd 30 (ANDROID_PROPERTY_WORKSPACE).
 * persist.* сохраняются в /data/property, как у настоящего init.
 */
class PropService(private val paths: VmPaths, private val log: (String) -> Unit) {
    @Volatile var area: PropArea? = null
        private set
    @Volatile var writes = 0L
        private set
    @Volatile var rejects = 0L
        private set
    /** ctl.start / ctl.stop от гостя */
    var onCtl: ((start: Boolean, service: String) -> Unit)? = null
    /** наблюдатель за изменением свойств (например sys.boot_completed) */
    var onSet: ((String, String) -> Unit)? = null

    private val server = UnixServer(paths.socket("property_service"), "props") { c ->
        val din = DataInputStream(c.inputStream)
        // Added for AEmulator Plus (2026-11-20): the legacy setprop message is { u32 cmd; char name[N]; char value[92] }. N is 32 in classic
        // bionic and 128 in the Android-x86 5.1 libc (224 bytes in all); it is the name field of the property area (LibcPropLayout).
        val nameMax = (area as? PropAreaV1)?.nameMax ?: LibcPropLayout.CLASSIC_NAME_MAX
        val msgSize = 4 + nameMax + 92
        val body = ByteArray(maxOf(128, msgSize))
        din.readFully(body, 0, 4)
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.getInt()
        // Added for AEmulator Plus (2026-11-15): the libcutils of pre-1.0 builds has no shared property area; it asks this
        // socket (cmd 2 = get, 3 = set, 6 = list) and waits for the answer. Without it the runtime could not read
        // runtime.vm (jam / dalvik), and the 12-byte request below was waited on for 128 bytes that never came.
        if (LegacyPropProtocol.isLegacyCmd(cmd)) {
            LegacyPropProtocol.serve(c.inputStream, c.outputStream, cmd, { name -> get(name) }, { name, value -> apply(name, value) }, log)
            return@UnixServer
        }
        if (cmd == 0x00020001) {
            // protocol 2 (8.0+, taken when ro.property_service.version is 2): lengths before the name and the value,
            // so a name may be longer than 31 characters, and the client waits for a result
            fun str(): String {
                val l = ByteArray(4); din.readFully(l)
                val n = ByteBuffer.wrap(l).order(ByteOrder.LITTLE_ENDIAN).getInt()
                if (n < 0 || n > 0xffff) throw java.io.IOException("props: length $n")
                return String(ByteArray(n).also { din.readFully(it) }, Charsets.UTF_8).substringBefore('\u0000')
            }
            val name = str()
            apply(name, str())
            c.outputStream.write(ByteArray(4))   // PROP_SUCCESS
            return@UnixServer
        }
        din.readFully(body, 4, msgSize - 4)
        val name = cstr(body, 4, nameMax)
        val value = cstr(body, 4 + nameMax, 92)
        if (cmd == 1) apply(name, value) else log("props: unknown command $cmd")
    }

    /** Разворачивает область из шаблона образа и подмешивает сохранённые persist.*. */
    @Synchronized
    fun prepare(overrides: Map<String, String>, defaults: Map<String, String> = emptyMap(), size: Int = PropArea.SIZE): Boolean {
        close()
        val tpl = paths.propsTemplate
        val f = paths.props
        if (!tpl.isFile) {
            log("props: no template ${tpl.name}")
            return false
        }
        f.parentFile?.mkdirs()
        tpl.copyTo(f, overwrite = true)
        // (a larger area for a kernel that asks for one: the C library maps the file whole, whatever its length)
        if (f.length() < size) runCatching { java.io.RandomAccessFile(f, "rw").use { it.setLength(size.toLong()) } }
        rootOwned(f)
        val a = PropArea.open(f)
        if (a == null) {
            log("props: area not recognized")
            return false
        }
        area = a
        val pd = File(paths.root, "data/property").apply { mkdirs() }
        var n = 0
        pd.listFiles()?.forEach { p ->
            if (p.isFile && p.name.startsWith("persist.")) {
                if (a.put(p.name, runCatching { p.readText().trim() }.getOrDefault(""))) n++
            }
        }
        for ((k, v) in defaults) if (a.get(k) == null) a.put(k, v)
        // наши значения важнее сохранённых гостем
        for ((k, v) in overrides) a.put(k, v)
        writes = 0; rejects = 0
        log("props: ${a.count} entries, ${a.room()} free" + if (n > 0) ", saved persist.* $n" else "")
        return true
    }

    fun serve(): Boolean {
        // Added for AEmulator Plus (2026-11-19): the handlers below run on this thread, one at a time and in order, never under
        // the property lock: a slow ctl.* / boot observer used to hold every guest setprop (system_server's PowerManagerService
        // included) until the watchdog killed system_server.
        synchronized(eventsLock) {
            if (events?.isShutdown != false) events = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "props-events").apply { isDaemon = true } }
        }
        return server.start(log)
    }

    private val eventsLock = Any()
    @Volatile private var events: java.util.concurrent.ExecutorService? = null

    private fun post(what: String, block: () -> Unit) {
        val run = Runnable { try { block() } catch (e: Throwable) { log("props: $what failed: $e") } }
        val ex = events
        if (ex == null || ex.isShutdown) run.run() else try { ex.execute(run) } catch (_: java.util.concurrent.RejectedExecutionException) { run.run() }
    }

    fun get(name: String): String? = area?.get(name)

    fun put(name: String, value: String): Boolean = area?.put(name, value) ?: false

    /**
     * Properties the guest may not change: a set of them is refused whatever the value. x86 firmware has only the AOSP
     * software renderer (OpenGL ES 1), and 7.0+ libEGL writes qemu.gles=2 itself in "vendor software renderer" mode;
     * ThreadedRenderer.isAvailable() then takes that for ES 2 support and every app's hwui aborts in eglChooseConfig
     * ("Failed to choose config, error = EGL_SUCCESS"). Pinned at 0, apps draw in software.
     */
    @Volatile var pinned: Set<String> = emptySet()

    @Synchronized
    private fun apply(name: String, value: String) {
        if (name.isEmpty()) { rejects++; return }
        if (name in pinned) { rejects++; return }
        if (name.startsWith("ctl.")) {
            val what = name.removePrefix("ctl.")
            // 10+: hwservicemanager asks for the service of an interface by the interface's name until it registers;
            // those services are started at boot here
            // (14+: servicemanager starts a lazy AIDL service the same way, as aidl/<name>; that one is not running yet)
            if (what == "interface_start" && value.startsWith("aidl/")) { post("ctl.$what") { onCtl?.invoke(true, value) }; return }
            if (what.startsWith("interface_")) return
            val h = onCtl
            if (h == null) log("props: ctl.$what=$value (no services)")
            else post("ctl.$what") { h(what == "start" || what == "restart", value) }
            return
        }
        if (name.startsWith("ro.") && get(name) != null) { rejects++; return }
        if (!put(name, value)) {
            rejects++
            log("props: failed to write $name=$value (out of space?)")
            return
        }
        writes++
        if (name.startsWith("persist.")) {
            runCatching { File(paths.root, "data/property/$name").writeText(value) }
        }
        onSet?.let { h -> post("set $name") { h(name, value) } }
    }

    @Synchronized
    fun close() {
        area?.close()
        area = null
    }

    fun stop() {
        synchronized(eventsLock) { events?.shutdownNow(); events = null }
        server.stop()
        close()
    }

    /** Отдельная копия области для mediaserver с ro.kernel.qemu=1 (эмуляторный звук). */
    fun privateCopy(name: String, extra: Map<String, String>): File? {
        val src = paths.props
        val dst = File(paths.bin, "props.$name")
        return runCatching {
            src.copyTo(dst, overwrite = true)
            rootOwned(dst)
            PropArea.open(dst)?.use { a -> extra.forEach { (k, v) -> a.put(k, v) } }
            dst
        }.getOrNull()
    }

    /**
     * The area is a fresh file every boot, and its inode may be one the owners table (dev, ino → uid) still
     * maps to an app: 7.0+ libc refuses a property area not owned by root, and every process then sees no
     * properties. A later row wins, so the file is pinned to root here.
     */
    private fun rootOwned(f: File) {
        runCatching {
            val st = android.system.Os.lstat(f.absolutePath)
            paths.owners.appendText(String.format("%016x %016x %08x %08x\n", st.st_dev, st.st_ino, 0, 0))
        }
    }

    private fun cstr(b: ByteArray, at: Int, max: Int): String {
        var n = 0
        while (n < max && b[at + n] != 0.toByte()) n++
        return String(b, at, n, Charsets.UTF_8)
    }
}
