package app.aemu.core

import java.io.File

/**
 * Android L preview (LPW39): /system/bin/dex2oat finishes the boot image ("dex2oat took 73s" in the log) and then
 * dies with SIGABRT inside exit(): libsigchain logs "Unable to find next sigaction in signal chain" and abort()s
 * (its dlsym(RTLD_NEXT, "sigaction") finds nothing under qemu). libart's GenerateImage() only looks at the exit
 * status, so it reports "Failed to generate image", zygote aborts (code 134) and the VM ends in FAILED, although
 * boot.art / boot.oat were completely written before exit() was called.
 *
 * dex2oat is replaced by a tiny shell wrapper around the original (dex2oat.real). It passes everything through and
 * turns "killed by SIGABRT (134)" into success only when the output it was asked to produce exists and is not empty.
 * Any other failure (a crash while compiling, so no output yet) keeps its exit status.
 * The same abort hits installd's per-app dex2oat, which passes --oat-fd instead of --oat-file; the fd is checked too.
 */
object Lpw39Dex2oat {
    const val MARKER = "# aemu-lpw39-dex2oat-wrapper"
    private const val REAL = "dex2oat.real"

    private const val ZIP = "/system/framework/aemu-imgclasses.zip"
    private const val LOCK = "/data/dalvik-cache/.aemu-image.lock"

    // Changed 2026-11-24 (AAU70, the API 19 "L" build): for the boot image run (--image=) the wrapper also
    //  - pins --image-classes-zip to the core-library list GuestVm writes (libart's own command line names framework.jar's
    //    full preloaded-classes, which makes dex2oat die in Class.forName of a WebView class; no dependence on libart
    //    honouring dalvik.vm.image-dex2oat-flags),
    //  - adds --compiler-filter=interpret-only when the binary offers it and nobody set a filter. The boot image then holds no
    //    compiled code: with the filter missing, the Quick compiler of the AAU70 build died with SIGSEGV in the "Compiler
    //    driver" thread (null EDX, fault address 0xc) while compiling the core libraries; the guest runs -Xint anyway.
    //    Whether the binary offers the option is decided by [offersCompilerFilter] when the wrapper is written, NOT by
    //    running "dex2oat.real --help" inside the wrapper: that usage text goes to logcat, never to stdout/stderr, so a grep
    //    over it could not match and the filter was silently never added (first version of this change, same day),
    //  - lets one image build run at a time: zygote, aemu-sensorhub and aemu-bt each start a runtime within a second of
    //    each other and each exec'd its own dex2oat on the same boot.art. The later ones wait, then find the image done.
    fun script(interpretOnly: Boolean = true): String {
        val filterOn = if (interpretOnly) "1" else ""
        return listOf(
        "#!/system/bin/sh",
        MARKER,
        "img=; oat=; fd=; cf=",
        "for a in \"\$@\"; do",
        "  case \"\$a\" in",
        "    --image=*) img=\${a#--image=} ;;",
        "    --oat-file=*) oat=\${a#--oat-file=} ;;",
        "    --oat-fd=*) fd=\${a#--oat-fd=} ;;",
        "    --compiler-filter=*) cf=1 ;;",
        "  esac",
        "done",
        "lock=",
        "fin() { [ -n \"\$lock\" ] && rm -r \"\$lock\" 2>/dev/null; exit \$1; }",
        "if [ -n \"\$img\" ]; then",
        "  lock=$LOCK",
        "  w=0",
        "  while ! mkdir \"\$lock\" 2>/dev/null; do",
        "    p=\$(cat \"\$lock/pid\" 2>/dev/null)",
        "    if [ -n \"\$p\" ] && [ ! -d \"/proc/\$p\" ]; then rm -r \"\$lock\" 2>/dev/null; continue; fi",
        "    w=\$((w+1))",
        "    if [ \$w -gt 7200 ]; then lock=; break; fi",
        "    sleep 1",
        "  done",
        "  [ -n \"\$lock\" ] && echo \$\$ > \"\$lock/pid\"",
        "  if [ \$w -gt 0 ] && [ -s \"\$img\" ] && [ -n \"\$oat\" ] && [ -s \"\$oat\" ]; then fin 0; fi",
        "  zip=; [ -s $ZIP ] && zip=1",
        "  addcf=; [ -z \"\$cf\" ] && addcf=$filterOn",
        "  n=\$#",
        "  while [ \$n -gt 0 ]; do",
        "    a=\"\$1\"; shift",
        "    case \"\$a\" in",
        "      --image-classes-zip=*) [ -n \"\$zip\" ] && a=--image-classes-zip=$ZIP ;;",
        "    esac",
        "    set -- \"\$@\" \"\$a\"",
        "    n=\$((n-1))",
        "  done",
        "  [ -n \"\$addcf\" ] && set -- \"\$@\" --compiler-filter=interpret-only",
        "fi",
        "/system/bin/$REAL \"\$@\"",
        "rc=\$?",
        "[ \$rc -eq 134 ] || fin \$rc",
        "if [ -n \"\$img\" ]; then [ -s \"\$img\" ] || fin \$rc; fi",
        "if [ -n \"\$oat\" ]; then [ -s \"\$oat\" ] || fin \$rc",
        "elif [ -n \"\$fd\" ]; then [ -s \"/proc/\$\$/fd/\$fd\" ] || fin \$rc",
        "else fin \$rc; fi",
        "fin 0",
        ""
    ).joinToString("\n")
    }

    /**
     * True when the firmware's dex2oat knows --compiler-filter: the option name is in the binary's strings (its option parser
     * and its usage text). Read from the file, because the binary can not be run from here and its usage output is not capturable
     * from a shell script (it goes to logcat).
     */
    fun offersCompilerFilter(bin: File): Boolean = runCatching {
        val needle = "--compiler-filter=".toByteArray(Charsets.ISO_8859_1)
        bin.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            var carry = 0                       // the tail of the previous chunk: the needle may straddle two reads
            var found = false
            while (!found) {
                val n = i.read(buf, carry, buf.size - carry)
                if (n < 0) break
                val end = carry + n
                var at = 0
                while (!found && at <= end - needle.size) {
                    var k = 0
                    while (k < needle.size && buf[at + k] == needle[k]) k++
                    found = k == needle.size
                    at++
                }
                carry = minOf(needle.size - 1, end)
                System.arraycopy(buf, end - carry, buf, 0, carry)
            }
            found
        }
    }.getOrDefault(false)

    /** True when [f] is already our wrapper. */
    fun isWrapper(f: File): Boolean =
        f.isFile && f.length() < 4096 && runCatching { f.readText(Charsets.ISO_8859_1).contains(MARKER) }.getOrDefault(false)

    /**
     * Installs the wrapper under [root]/system/bin. Returns true when something changed, false when it was already
     * in place or there is no dex2oat. Safe to call on every boot.
     */
    fun install(root: File): Boolean {
        val bin = File(root, "system/bin")
        val d2o = File(bin, "dex2oat")
        val real = File(bin, REAL)
        if (!d2o.exists() && !real.isFile) return false
        var changed = false
        if (d2o.exists() && !isWrapper(d2o)) {
            // the firmware's own binary (first run, or the image was imported again): keep it as dex2oat.real
            real.delete()
            if (!d2o.renameTo(real)) return false
            changed = true
        }
        if (!real.isFile) return false
        val wanted = script(offersCompilerFilter(real))
        if (!isWrapper(d2o) || d2o.readText(Charsets.ISO_8859_1) != wanted) {
            d2o.delete()
            d2o.writeText(wanted, Charsets.ISO_8859_1)
            changed = true
        }
        for (f in listOf(d2o, real)) { f.setReadable(true, false); f.setExecutable(true, false) }
        return changed
    }
}
