/* Modified for AEmulator Plus, 2026-11-25: static iWMMXt/iWMMXt2 -> ARMv7 NEON translator.
 * GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.File
import java.io.RandomAccessFile

/** The library has iWMMXt code that cannot be translated safely (the caller leaves the file as it is). */
class IwmmxtUnsupported(message: String) : Exception(message)

/**
 * Marvell (PXA/Armada) firmware ships Skia blitters in hand-written Wireless MMX assembly; no QEMU CPU model has both
 * iWMMXt and VFP/NEON, so the guest dies with SIGILL in libskia (e.g. 0xee377007, a coprocessor-0 CDP) and the boot loops.
 *
 * This rewrites such a library before boot, once, without any run-time cost:
 *  - every reachable iWMMXt/iWMMXt2 instruction is decoded and replaced by the equivalent ARMv7 NEON code;
 *  - wR0..wR15 live in d16..d31, wCGR0..3 in d4..d7 (negated 16-bit counts, so a right shift is one VSHL), d0..d3 are scratch,
 *    all of them caller-saved in the AAPCS;
 *  - an instruction that becomes exactly one VFP-class instruction is patched in place (those carry a condition code);
 *    anything longer becomes a code cave in an extra PT_LOAD segment (a converted PT_GNU_STACK/PT_NOTE header), reached with
 *    a (conditional) branch and left with a branch back;
 *  - the SIMD flag instructions TANDC/TORC are fused with the instruction that produced the flags and rebuild N/Z in the CPSR.
 *
 * Nothing here is hand-encoded: the decode rows and the ARM/VFP/NEON encodings in [IwmmxtTables] are measured from GNU as,
 * and the translation was verified bit for bit against an architectural model (see tools/iwmmxt).
 */
object Iwmmxt {
    class Result(val data: ByteArray, val instructions: Int, val inPlace: Int, val caves: Int, val caveBytes: Int, val skipped: Int = 0, val jumps: Int = 0) {
        fun summary() = "$instructions instructions to NEON ($inPlace in place, $caves in caves, ${caveBytes / 1024} KB)" +
            (if (skipped > 0) ", $skipped data words left alone" else "") + (if (jumps > 0) ", $jumps computed jumps resolved through the library's pointers" else "")
    }

    /** True when the file is a 32-bit ARM ELF whose build attributes say Tag_WMMX_arch (reads only the headers). */
    fun hasWmmx(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            wmmxAttribute { off, len ->
                if (off < 0 || len < 0 || off + len > raf.length()) null
                else ByteArray(len).also { raf.seek(off); raf.readFully(it) }
            }
        }
    }.getOrDefault(false)

    fun hasWmmx(elf: ByteArray): Boolean = wmmxAttribute { off, len ->
        if (off < 0 || len < 0 || off + len > elf.size) null else elf.copyOfRange(off.toInt(), (off + len).toInt())
    }

    /**
     * Prelinked 2.x libraries end with <load address> + "PRE " (the linker reads the last 8 bytes of the file; without the tag it
     * treats the library as not prelinked, maps it in its own range and relocates data that prelink has already relocated, so
     * constructors and the GOT point into nowhere). The translator must keep these 8 bytes last.
     */
    fun hasPrelinkTag(b: ByteArray): Boolean = b.size >= 8 && b[b.size - 4].toInt() == 0x50 && b[b.size - 3].toInt() == 0x52 &&
        b[b.size - 2].toInt() == 0x45 && b[b.size - 1].toInt() == 0x20             // "PRE "

    /** True when [translated] (a library written by an earlier build, which appended the code after the tag) lost the tag its [original] had. */
    /** A PT_LOAD with no bytes (written by earlier builds when a library needed no cave): the 2.x linker cannot map it. */
    fun hasEmptyLoad(b: ByteArray): Boolean = runCatching {
        if (b.size < 52) return false
        val phoff = u32(b, 28); val ent = u16(b, 42); val n = u16(b, 44)
        for (i in 0 until n) {
            val o = phoff + i * ent
            if (o + 32 > b.size) return false
            if (u32(b, o) == 1 && u32(b, o + 16) == 0 && u32(b, o + 20) == 0) return true
        }
        false
    }.getOrDefault(false)

    fun prelinkTagLost(original: ByteArray, translated: ByteArray): Boolean = hasPrelinkTag(original) && !hasPrelinkTag(translated)

    /** null: the library has no reachable iWMMXt code. Throws [IwmmxtUnsupported] when it has some that cannot be translated. */
    fun translate(elf: ByteArray): Result? {
        if (!hasWmmx(elf)) return null
        return Patcher(elf).run()
    }

    // ------------------------------------------------------------------------------------------------ small helpers
    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }

    private fun cstr(b: ByteArray, o: Int): String {
        var e = o
        while (e < b.size && b[e].toInt() != 0) e++
        return String(b, o, e - o, Charsets.ISO_8859_1)
    }

    private fun wmmxAttribute(read: (Long, Int) -> ByteArray?): Boolean {
        val eh = read(0, 52) ?: return false
        if (eh[0].toInt() != 0x7f || eh[1].toInt() != 0x45 || eh[4].toInt() != 1 || eh[5].toInt() != 1 || u16(eh, 18) != 40) return false
        val shoff = u32(eh, 32).toLong() and 0xffffffffL
        val shentsize = u16(eh, 46); val shnum = u16(eh, 48)
        if (shoff == 0L || shentsize < 40 || shnum == 0 || shnum > 4096) return false
        val sh = read(shoff, shentsize * shnum) ?: return false
        for (i in 0 until shnum) {
            val o = i * shentsize
            if (u32(sh, o + 4) != 0x70000003) continue                 // SHT_ARM_ATTRIBUTES
            val off = u32(sh, o + 16).toLong() and 0xffffffffL; val size = u32(sh, o + 20)
            if (size <= 0 || size > (1 shl 20)) return false
            val a = read(off, size) ?: return false
            return parseAttributes(a)
        }
        return false
    }

    private fun parseAttributes(a: ByteArray): Boolean {
        if (a.isEmpty() || a[0].toInt() != 0x41) return false
        var p = 1
        while (p + 4 <= a.size) {
            val secLen = u32(a, p); if (secLen < 5 || p + secLen > a.size) return false
            val secEnd = p + secLen
            var q = p + 4
            val vendor = cstr(a, q); q += vendor.length + 1
            if (vendor == "aeabi") {
                while (q + 5 <= secEnd) {
                    val subTag = a[q].toInt() and 0xff; val subLen = u32(a, q + 1)
                    if (subLen < 5 || q + subLen > secEnd) return false
                    if (subTag == 1) {                                    // Tag_File
                        var r = q + 5; val end = q + subLen
                        while (r < end) {
                            var tag = 0; var sh = 0
                            while (true) { val x = a[r++].toInt() and 0xff; tag = tag or ((x and 0x7f) shl sh); sh += 7; if (x and 0x80 == 0) break }
                            if (tag == 4 || tag == 5 || tag == 65 || tag == 67 || (tag >= 32 && tag and 1 == 1 && tag != 32)) {
                                while (r < end && a[r].toInt() != 0) r++; r++   // NTBS
                            } else {
                                var v = 0L; sh = 0
                                while (true) { val x = a[r++].toInt() and 0xff; v = v or ((x and 0x7f).toLong() shl sh); sh += 7; if (x and 0x80 == 0) break }
                                if (tag == 32) { while (r < end && a[r].toInt() != 0) r++; r++ }
                                if (tag == 11 && v != 0L) return true         // Tag_WMMX_arch
                            }
                        }
                    }
                    q += subLen
                }
            }
            p = secEnd
        }
        return false
    }

    // ------------------------------------------------------------------------------------------------ ELF
    private class Seg(val index: Int, val type: Int, val off: Int, val vaddr: Int, val filesz: Int, val memsz: Int, val flags: Int)
    private class Sec(val name: String, val type: Int, val flags: Int, val addr: Int, val off: Int, val size: Int)

    private class Elf(val b: ByteArray) {
        val phoff: Int; val phentsize: Int; val phnum: Int
        val segs = ArrayList<Seg>(); val secs = ArrayList<Sec>()

        init {
            if (b.size < 52 || b[0].toInt() != 0x7f || b[4].toInt() != 1 || b[5].toInt() != 1 || u16(b, 18) != 40) throw IwmmxtUnsupported("not a 32-bit ARM ELF")
            phoff = u32(b, 28); phentsize = u16(b, 42); phnum = u16(b, 44)
            for (i in 0 until phnum) {
                val o = phoff + i * phentsize
                segs.add(Seg(i, u32(b, o), u32(b, o + 4), u32(b, o + 8), u32(b, o + 16), u32(b, o + 20), u32(b, o + 24)))
            }
            val shoff = u32(b, 32); val shentsize = u16(b, 46); val shnum = u16(b, 48); val shstrndx = u16(b, 50)
            if (shoff != 0 && shnum > 0 && shshdrOk(shoff, shentsize, shnum)) {
                val strOff = if (shstrndx < shnum) u32(b, shoff + shstrndx * shentsize + 16) else 0
                for (i in 0 until shnum) {
                    val o = shoff + i * shentsize
                    secs.add(Sec(if (strOff > 0) cstr(b, strOff + u32(b, o)) else "", u32(b, o + 4), u32(b, o + 8), u32(b, o + 12), u32(b, o + 16), u32(b, o + 20)))
                }
            }
        }

        private fun shshdrOk(shoff: Int, ent: Int, n: Int) = shoff > 0 && ent >= 40 && shoff.toLong() + ent.toLong() * n <= b.size
    }

    // ------------------------------------------------------------------------------------------------ decode
    private class Ins(val addr: Int, val base: String, val cc: Int, val ops: String)

    /** Tooling (tools/iwmmxt): the NEON lines one iWMMXt word becomes; [prevWord] is the flag producer before a tandc/torc/textrc. null: no decoder row. */
    internal fun linesFor(w: Int, prevWord: Int = 0): List<String>? {
        val ins = decode(0, w) ?: return null
        val prev = if (prevWord != 0) decode(-4, prevWord) else null
        return xlate(ins, prev).lines.map { it.text.replace("{c}", "") }
    }

    /** Tooling: the word the built-in assembler gives a translated line (checked against GNU as by tools/iwmmxt). */
    internal fun encodeLine(line: String): Int = Asm.encode(line)

    /** Tooling: every line shape the translator can emit, from synthetic operands of every decoder row (feeds gen_tables.py). */
    internal fun allLines(): Set<String> {
        val out = java.util.TreeSet<String>()
        val pats = listOf(mapOf("d" to 1, "n" to 2, "m" to 3, "k" to 1, "a" to 4, "b" to 5), mapOf("d" to 1, "n" to 1, "m" to 3, "k" to 2, "a" to 4, "b" to 5),
            mapOf("d" to 2, "n" to 1, "m" to 2, "k" to 3, "a" to 12, "b" to 5))
        for (r in decRows) {
            val nb = Regex("\\{i\\}").containsMatchIn(r.tmpl)
            val ivs = if (nb) listOf(0, 1, 2, 3, 4, 5, 7, 8, 15, 16, 31, 32, 63) else listOf(0)
            for (pt in pats) for (iv in ivs) {
                var ops = r.tmpl
                for ((k, v) in pt) ops = ops.replace("{$k}", v.toString())
                ops = ops.replace("{i}", iv.toString())
                if (ops.contains("{")) continue
                val prevIns = Ins(-4, "wxor", 14, "wr3, wr4, wr5")
                try { for (t in xlate(Ins(0, r.name, 14, ops), prevIns).lines) out.add(t.text.replace("{c}", "")) } catch (e: IwmmxtUnsupported) {}
                if (r.name.startsWith("tandc") || r.name.startsWith("torc") || r.name.startsWith("textrc")) {
                    for (prod in listOf("wcmpeqb", "wcmpeqh", "wcmpeqw", "waddh", "wunpckilw", "wxor", "wsubh")) {
                        try { for (t in xlate(Ins(0, r.name, 14, ops), Ins(-4, prod, 14, "wr3, wr4, wr5")).lines) out.add(t.text.replace("{c}", "")) } catch (e: IwmmxtUnsupported) {}
                    }
                }
            }
        }
        for (sz in "dwhb") for (off in listOf(0, 1, 2, 4, 8, 16, 48, 64, 600, 1020)) for (ld in listOf("wldr", "wstr")) {
            val forms = listOf("wr5, [r3, #$off]", "wr5, [r3], #$off", "wr5, [r3, #$off]!", "wr5, [r3, #-$off]", "wr5, [r3, #-$off]!", "wr5, [r3]", "wr5, [r3], #-8")
            for (f in forms) try { for (t in xlate(Ins(0, ld + sz, 14, f), null).lines) out.add(t.text.replace("{c}", "")) } catch (e: IwmmxtUnsupported) {}
        }
        for (ld in listOf("wldr", "wstr")) {
            for (f in listOf("wr5, [r3, +r4]", "wr5, [r3, -r4]", "wr5, [r3, +r4]!", "wr5, [r3, -r4]!", "wr5, [r3], +r4", "wr5, [r3], -r4"))
                try { for (t in xlate(Ins(0, ld + "d", 15, f), null).lines) out.add(t.text.replace("{c}", "")) } catch (e: IwmmxtUnsupported) {}
            for (off in listOf(0, 4, 48, 600)) for (f in listOf("wcgr1, [r3, #$off]", "wcgr1, [r3], #$off", "wcgr1, [r3, #$off]!", "wcgr1, [r3, #-$off]", "wcgr1, [r3]"))
                try { for (t in xlate(Ins(0, ld + "w", 15, f), null).lines) out.add(t.text.replace("{c}", "")) } catch (e: IwmmxtUnsupported) {}
        }
        return out
    }

    private class Field(val name: String, val bits: IntArray?, val tab: HashMap<Int, Int>?, val tabMask: Int)
    private class DecRow(val name: String, val tmpl: String, val mask: Int, val value: Int, val condF: Boolean, val fields: List<Field>)

    private val decRows: List<DecRow> by lazy {
        IwmmxtTables.DECODE.map { s ->
            val p = s.split("|")
            val fields = ArrayList<Field>()
            if (p[5].isNotEmpty()) for (f in p[5].split(";")) {
                val eq = f.indexOf('=')
                if (eq > 0 && (f.indexOf(':') < 0 || eq < f.indexOf(':'))) {
                    val tab = HashMap<Int, Int>(); var m = 0
                    for (e in f.substring(eq + 1).split(",")) { val c = e.indexOf(':'); val v = java.lang.Long.decode(e.substring(c + 1)).toInt(); tab[e.substring(0, c).toInt()] = v; m = m or v }
                    fields.add(Field(f.substring(0, eq), null, tab, m))
                } else {
                    val c = f.indexOf(':')
                    fields.add(Field(f.substring(0, c), f.substring(c + 1).split(",").map { it.toInt() }.toIntArray(), null, 0))
                }
            }
            DecRow(p[0], p[1], java.lang.Long.decode(p[2]).toInt(), java.lang.Long.decode(p[3]).toInt(), p[4] == "1", fields)
        }
    }

    private fun isCandidate(w: Int): Boolean {
        val top = (w ushr 24) and 0xf; val cp = (w ushr 8) and 0xf
        return cp <= 1 && (top == 0xe || top == 0xc || top == 0xd)
    }

    private fun decode(addr: Int, w: Int): Ins? {
        val cc = w ushr 28
        // wldr / wstr (LDC/STC on cp0/cp1)
        if (w and 0x0e000e00 == 0x0c000000 && w and 0x0fe00000 != 0x0c400000) {
            if (cc == 15) {
                // iWMMXt2 forms in the unconditional space: wldrw/wstrw of a wCGR, and wldrd/wstrd with a register offset
                if ((w ushr 8) and 1 == 0) return null
                val p = (w ushr 24) and 1; val u = (w ushr 23) and 1; val nn = (w ushr 22) and 1; val wb = (w ushr 21) and 1; val l = (w ushr 20) and 1
                val rn = (w ushr 16) and 0xf; val wd = (w ushr 12) and 0xf
                val reg = if (rn == 15) "pc" else "r$rn"
                val ld = if (l == 1) "wldr" else "wstr"
                if (nn == 0) {
                    if (wd !in 8..11) return null
                    val soff = if (u == 1) (w and 0xff) shl 2 else -((w and 0xff) shl 2)
                    val ops = when {
                        p == 1 && wb == 0 -> if (soff == 0) "wcgr${wd - 8}, [$reg]" else "wcgr${wd - 8}, [$reg, #$soff]"
                        p == 1 -> "wcgr${wd - 8}, [$reg, #$soff]!"
                        wb == 1 -> "wcgr${wd - 8}, [$reg], #$soff"
                        else -> return null
                    }
                    return Ins(addr, ld + "w", cc, ops)
                }
                if (w and 0xf0 != 0) return null
                val sg = if (u == 1) "+" else "-"; val rm = "r${w and 0xf}"
                val ops = when {
                    p == 1 && wb == 0 -> "wr$wd, [$reg, $sg$rm]"
                    p == 1 -> "wr$wd, [$reg, $sg$rm]!"
                    wb == 1 -> "wr$wd, [$reg], $sg$rm"
                    else -> return null
                }
                return Ins(addr, ld + "d", cc, ops)
            }
            val p = (w ushr 24) and 1; val u = (w ushr 23) and 1; val n = (w ushr 22) and 1; val wb = (w ushr 21) and 1; val l = (w ushr 20) and 1
            val rn = (w ushr 16) and 0xf; val wd = (w ushr 12) and 0xf; val cp1 = (w ushr 8) and 1
            if (rn == 15 && l == 0) return null                          // a store into the code: data that the walk ran into
            val sz = if (cp1 == 1) (if (n == 1) 'd' else 'w') else (if (n == 1) 'h' else 'b')
            val off = (w and 0xff) shl (if (cp1 == 1) 2 else 0)
            val soff = if (u == 1) off else -off
            val reg = if (rn == 15) "pc" else "r$rn"
            val ops = when {
                p == 1 && wb == 0 -> if (soff == 0) "wr$wd, [$reg]" else "wr$wd, [$reg, #$soff]"
                p == 1 -> "wr$wd, [$reg, #$soff]!"
                wb == 1 -> "wr$wd, [$reg], #$soff"
                else -> return null
            }
            return Ins(addr, (if (l == 1) "wldr" else "wstr") + sz, cc, ops)
        }
        // MCRR / MRRC (tmcrr / tmrrc) are table rows; everything else is table driven
        for (r in decRows) {
            if (r.condF != (cc == 15)) continue
            if (w and r.mask != r.value) continue
            var ops = r.tmpl
            for (f in r.fields) {
                var v = 0
                if (f.bits != null) { for (i in f.bits.indices) v = v or (((w ushr f.bits[i]) and 1) shl i) }
                else {
                    val fb = w and f.tabMask; var found = -1
                    for ((k, x) in f.tab!!) if (x == fb) { found = k; break }
                    if (found < 0) { v = -1 } else v = found
                }
                if (v < 0) { ops = ""; break }
                ops = ops.replace("{" + f.name + "}", v.toString())
            }
            if (ops.isEmpty() && r.fields.isNotEmpty()) continue
            return Ins(addr, r.name, cc, ops)
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------ assemble
    private class Slot(val lin: Boolean, val lim: Int, val masks: IntArray?, val tab: HashMap<Int, Int>?)
    private class EncRow(val base: Int, val slots: List<Slot>)

    private object Asm {
        private val rows: HashMap<String, EncRow> by lazy {
            val m = HashMap<String, EncRow>()
            for (s in IwmmxtTables.ENCODE) {
                val p = s.split("|")
                val slots = ArrayList<Slot>()
                if (p[2].isNotEmpty()) for (x in p[2].split(";")) {
                    if (x.startsWith("L:")) {
                        val r = x.substring(2); val c = r.indexOf(':')
                        slots.add(Slot(true, r.substring(0, c).toInt(), r.substring(c + 1).split(",").map { java.lang.Long.decode(it).toInt() }.toIntArray(), null))
                    } else {
                        val r = x.substring(2); val c = r.indexOf(':')
                        val tab = HashMap<Int, Int>()
                        for (e in r.substring(c + 1).split(",")) { val q = e.indexOf('='); tab[e.substring(0, q).toInt()] = java.lang.Long.decode(e.substring(q + 1)).toInt() }
                        slots.add(Slot(false, -1, null, tab))
                    }
                }
                m[p[0]] = EncRow(java.lang.Long.decode(p[1]).toInt(), slots)
            }
            m
        }
        private val WS = Regex("\\s+")
        private val token = java.util.regex.Pattern.compile("#-?(?:0x[0-9a-fA-F]+|\\d+)|\\b[dqr]\\d+\\b|\\[\\d+\\]")

        fun encode(line: String, cond: Int = 14): Int {
            val m = token.matcher(line.trim())
            val key = StringBuilder(); val vals = ArrayList<Int>()
            val src = line.trim(); var last = 0
            while (m.find()) {
                key.append(src, last, m.start()); last = m.end()
                val t = m.group()
                when {
                    t[0] == '#' -> {
                        val neg = t[1] == '-'; val body = if (neg) t.substring(2) else t.substring(1)
                        vals.add(if (body.startsWith("0x")) body.substring(2).toInt(16) else body.toInt()); key.append(if (neg) "#-?" else "#?")
                    }
                    t[0] == '[' -> { vals.add(t.substring(1, t.length - 1).toInt()); key.append("[?]") }
                    else -> { vals.add(t.substring(1).toInt()); key.append(t[0]).append('?') }
                }
            }
            key.append(src, last, src.length)
            val k = WS.replace(key.toString(), " ")
            val row = rows[k] ?: throw IwmmxtUnsupported("no encoding for '$line' ($k)")
            if (row.slots.size != vals.size) throw IwmmxtUnsupported("operand count for '$line'")
            var w = row.base
            for (i in vals.indices) {
                val s = row.slots[i]; val v = vals[i]
                if (s.lin) {
                    if (s.lim >= 0 && v > s.lim) throw IwmmxtUnsupported("operand $v out of range in '$line'")
                    val ms = s.masks!!
                    if (v ushr ms.size != 0) throw IwmmxtUnsupported("operand $v out of range in '$line'")
                    for (b in ms.indices) if ((v ushr b) and 1 == 1) {
                        if (ms[b] == 0) throw IwmmxtUnsupported("operand $v not encodable in '$line'")
                        w = w xor ms[b]
                    }
                } else {
                    val d = s.tab!![v] ?: throw IwmmxtUnsupported("operand $v not encodable in '$line'")
                    w = w xor d
                }
            }
            if (cond != 14) {
                if (row.base ushr 28 != 14) throw IwmmxtUnsupported("'$line' cannot be conditional")
                w = (w and 0x0fffffff) or (cond shl 28)
            }
            return w
        }
    }

    // ------------------------------------------------------------------------------------------------ translate
    private class T(val text: String, val vfp: Boolean = false)
    private class Xl(val lines: List<T>, val literal: Long? = null)

    private fun wr(n: Int) = "d${16 + n}"
    private fun cgr(k: Int) = "d${4 + k}"
    private fun sizeOf(c: Char) = when (c) { 'b' -> 8; 'h' -> 16; 'w' -> 32; else -> 64 }
    private fun wnum(s: String): Int {
        if (!s.startsWith("wr")) throw IwmmxtUnsupported("operand '$s'")
        return s.substring(2).toInt()
    }
    private fun imm(s: String): Int { if (!s.startsWith("#")) throw IwmmxtUnsupported("operand '$s'"); return s.substring(1).toInt() }

    private fun splitOps(s: String): List<String> {
        val out = ArrayList<String>(); var depth = 0; var cur = StringBuilder()
        for (c in s) {
            if (c == '[') depth++
            if (c == ']') depth--
            if (c == ',' && depth == 0) { out.add(cur.toString().trim()); cur = StringBuilder() } else cur.append(c)
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out
    }

    private val FLAG_LAYOUT = mapOf(
        "wunpckihw" to 32, "wunpckilw" to 32, "wxor" to 64, "wand" to 64, "wor" to 64, "wandn" to 64,
        "wsubh" to 16, "waddh" to 16, "wsllh" to 16, "wsrlh" to 16, "wunpckelub" to 16, "wunpckehub" to 16, "wsradg" to 64)

    private val DATA_NONPROD = listOf("wldr", "wstr", "tmcr", "tmrc", "tmrrc", "tmcrr", "tbcst", "textrm", "tinsr", "tandc", "torc", "textrc", "tmovmsk", "tmia")

    /** The lane size of the flags a producer leaves in wCASF (what tandc/torc/textrc read). */
    private fun layoutOf(base: String): Int? {
        FLAG_LAYOUT[base]?.let { return it }
        Regex("w(?:add|sub)([bhw])(?:us|ss)?").matchEntire(base)?.let { return sizeOf(it.groupValues[1][0]) }
        Regex("w(?:min|max)[us]([bhw])|wcmp(?:eq|gt[us])([bhw])").matchEntire(base)?.let { return sizeOf((it.groupValues[1] + it.groupValues[2])[0]) }
        Regex("w(?:srl|sll|sra|ror)([hwd])g?").matchEntire(base)?.let { return sizeOf(it.groupValues[1][0]) }
        Regex("wunpck(e)[lh][us]([bhw])").matchEntire(base)?.let { return 2 * sizeOf(it.groupValues[2][0]) }
        Regex("wunpcki[lh]([bhw])").matchEntire(base)?.let { return sizeOf(it.groupValues[1][0]) }
        return null
    }

    private val reMem = Regex("wr(\\d+),\\s*\\[(\\w+)(?:,\\s*#(-?\\d+))?\\](!)?(?:,\\s*#(-?\\d+))?")

    /** r += off, in steps an ARM immediate can hold. */
    private fun adjT(r: String, off: Int): List<T> {
        val out = ArrayList<T>(); var a = Math.abs(off); val op = if (off >= 0) "add" else "sub"
        while (a > 0) { val c = minOf(a, 255); out.add(T("$op $r, $r, #$c")); a -= c }
        return out
    }
    private fun adjRegT(r: String, rm: String, plus: Boolean) = T("${if (plus) "add" else "sub"} $r, $r, $rm")

    private val reMemReg = Regex("wr(\\d+),\\s*\\[(\\w+),\\s*([+-])(r\\d+)\\](!)?")
    private val reMemRegPost = Regex("wr(\\d+),\\s*\\[(\\w+)\\],\\s*([+-])(r\\d+)")
    private val reMemCgr = Regex("wcgr(\\d),\\s*\\[(\\w+)(?:,\\s*#(-?\\d+))?\\](!)?(?:,\\s*#(-?\\d+))?")

    /** wldrd / wstrd wRd, [Rn, +-Rm] | [Rn, +-Rm]! | [Rn], +-Rm */
    private fun xlateMemReg(ld: Boolean, wrn: Int, r: String, plus: Boolean, rm: String, mode: String): Xl {
        if (r == "pc" || r == rm) throw IwmmxtUnsupported("register-offset wldrd/wstrd form")
        val op = if (ld) "vld1" else "vst1"; val d = wr(wrn)
        if (mode == "post") return if (plus) Xl(listOf(T("$op.64 {$d}, [$r], $rm"))) else Xl(listOf(T("$op.64 {$d}, [$r]"), adjRegT(r, rm, false)))
        val l = arrayListOf(adjRegT(r, rm, plus), T("$op.64 {$d}, [$r]"))
        if (mode == "off") l.add(adjRegT(r, rm, !plus))
        return Xl(l)
    }

    private fun xlateMem(ins: Ins): Xl {
        val b = ins.base; val sz = b[b.length - 1]; val ld = b.startsWith("wldr")
        reMemReg.matchEntire(ins.ops)?.let { m -> return xlateMemReg(ld, m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3] == "+", m.groupValues[4], if (m.groupValues[5].isNotEmpty()) "pre" else "off") }
        reMemRegPost.matchEntire(ins.ops)?.let { m -> return xlateMemReg(ld, m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3] == "+", m.groupValues[4], "post") }
        val cg = reMemCgr.matchEntire(ins.ops)
        val m = cg ?: reMem.matchEntire(ins.ops) ?: throw IwmmxtUnsupported("mem ${ins.ops}")
        val wrn = m.groupValues[1].toInt(); val r = m.groupValues[2]
        val offS = m.groupValues[3]; val bang = m.groupValues[4].isNotEmpty(); val postS = m.groupValues[5]
        val mode: String; val off: Int
        if (postS.isNotEmpty()) { mode = "post"; off = postS.toInt() } else if (bang) { mode = "pre"; off = offS.toInt() } else { mode = "off"; off = if (offS.isEmpty()) 0 else offS.toInt() }
        if (r == "pc") {
            // a constant loaded from the literal pool: vldr has the same pc-relative form
            if (cg == null && sz == 'd' && ld && mode == "off" && off % 4 == 0 && off >= -1020 && off <= 1020) return Xl(listOf(T("vldr{c} ${wr(wrn)}, [pc, #$off]", true)))
            throw IwmmxtUnsupported("pc-relative wldr/wstr")
        }
        val d = if (cg != null) "d0" else wr(wrn)
        if (cg == null && sz == 'd') {
            val op = if (ld) "vldr" else "vstr"
            if (mode == "off" && off % 4 == 0 && off >= -1020 && off <= 1020)
                return Xl(listOf(T("$op{c} $d, [$r${if (off != 0) ", #$off" else ""}]", true)))
            if (mode == "post" && off == 8) return Xl(listOf(T("${if (ld) "vldmia" else "vstmia"}{c} $r!, {$d}", true)))
            if (mode == "pre" && off == -8 && !ld) return Xl(listOf(T("vstmdb{c} $r!, {$d}", true)))
            if (mode == "post") return Xl(listOf(T("$op $d, [$r]")) + adjT(r, off))
            if (mode == "pre") return Xl(adjT(r, off) + listOf(T("$op $d, [$r]")))
            throw IwmmxtUnsupported("wldrd form ${ins.ops}")
        }
        val n = if (cg != null) 32 else when (sz) { 'w' -> 32; 'h' -> 16; else -> 8 }
        val lane = "{$d[0]}"
        val out = ArrayList<T>()
        // a wCGR is kept as d4..d7: 16-bit lanes holding the negated count
        if (cg != null && !ld) { out.add(T("vneg.s16 d0, ${cgr(wrn)}")); out.add(T("vshl.i32 d0, d0, #16")); out.add(T("vshr.u32 d0, d0, #16")) }
        if (cg == null && ld) out.add(T("veor $d, $d, $d"))
        if (mode == "off" && off != 0) out.addAll(adjT(r, off))
        if (mode == "pre") out.addAll(adjT(r, off))
        val vop = if (ld) "vld1" else "vst1"
        if (mode == "post" && off == n / 8) out.add(T("$vop.$n $lane, [$r]!"))
        else {
            out.add(T("$vop.$n $lane, [$r]"))
            if (mode == "post") out.addAll(adjT(r, off))
        }
        if (mode == "off" && off != 0) out.addAll(adjT(r, -off))
        if (cg != null && ld) { out.add(T("vdup.16 ${cgr(wrn)}, d0[0]")); out.add(T("vneg.s16 ${cgr(wrn)}, ${cgr(wrn)}")) }
        return Xl(out)
    }

    private fun flagLines(layout: Int, x: String, comb: String, tmp: String): List<String> {
        val mm = if (comb == "and") "vpmin" else "vpmax"
        val l = ArrayList<String>()
        when (layout) {
            32 -> l.addAll(listOf("vshr.u32 d0, $x, #31", "vceq.i32 d1, $x, #0", "vshr.u32 d1, d1, #31",
                "$mm.u32 d0, d0, d0", "$mm.u32 d1, d1, d1", "vshl.i32 d0, d0, #31", "vshl.i32 d1, d1, #30", "vorr d0, d0, d1", "vmov.32 $tmp, d0[0]"))
            64 -> {
                if (comb == "and") throw IwmmxtUnsupported("tandcw after 64-bit producer")
                l.addAll(listOf("vshr.u64 d0, $x, #63", "vshl.i32 d0, d0, #31", "vceq.i32 d1, $x, #0", "vpmin.u32 d1, d1, d1", "vshr.u32 d1, d1, #31",
                    "vshl.i32 d1, d1, #30", "vorr d0, d0, d1", "vmov.32 $tmp, d0[0]"))
            }
            8 -> l.addAll(listOf("vshr.u8 d0, $x, #7", "vceq.i8 d1, $x, #0", "vshr.u8 d1, d1, #7",
                "$mm.u8 d0, d0, d0", "$mm.u8 d0, d0, d0", "$mm.u8 d0, d0, d0", "$mm.u8 d1, d1, d1", "$mm.u8 d1, d1, d1", "$mm.u8 d1, d1, d1",
                "vshl.i8 d0, d0, #7", "vshl.i8 d1, d1, #6", "vorr d0, d0, d1", "vmov.u8 $tmp, d0[0]", "lsl $tmp, $tmp, #24"))
            16 -> l.addAll(listOf("vshr.u16 d0, $x, #15", "vceq.i16 d1, $x, #0", "vshr.u16 d1, d1, #15",
                "$mm.u16 d0, d0, d0", "$mm.u16 d0, d0, d0", "$mm.u16 d1, d1, d1", "$mm.u16 d1, d1, d1",
                "vshl.i16 d0, d0, #15", "vshl.i16 d1, d1, #14", "vorr d0, d0, d1", "vmov.u16 $tmp, d0[0]", "lsl $tmp, $tmp, #16"))
            else -> throw IwmmxtUnsupported("flag layout")
        }
        l.add("msr APSR_nzcvq, $tmp")
        return l
    }

    private val reLogic = Regex("w(and|or|xor|andn)")
    private val reAddSub = Regex("w(add|sub)([bhw])(us|ss|)")
    private val reMul = Regex("wmul([us])([lm])(r?)")
    private val reMadd = Regex("wmadd([us])")
    private val reMac = Regex("wmac([us])(z?)")
    private val reUnpack = Regex("wunpck(e|i)(l|h)([us]?)([bhw])")
    private val rePack = Regex("wpack([hwd])(us|ss)")
    private val reShift = Regex("w(srl|sll|sra|ror)([hwd])")
    private val reShiftG = Regex("w(srl|sll|sra)([hwd])g")
    private val reMinMax = Regex("w(min|max)([us])([bhw])")
    private val reCmpGt = Regex("wcmpgt([us])([bhw])")
    private val reCmpEq = Regex("wcmpeq([bhw])")
    private val reAlignR = Regex("walignr([0-3])")
    private val reBcst = Regex("tbcst([bhw])")
    private val reExtr = Regex("textrm([us])([bhw])")
    private val reInsr = Regex("tinsr([bhw])")
    private val reFlagC = Regex("t(and|or)c([bhw])")
    private val reWcgr = Regex("wcgr(\\d)")
    private val reMulW = Regex("wmulw([us])m(r?)")
    private val reAvg2 = Regex("wavg2([bh])(r?)")
    private val reSad = Regex("wsad([bh])(z?)")
    private val reMovmsk = Regex("tmovmsk([bhw])")
    private val reTmia = Regex("tmia(ph|bb|bt|tb|tt)?")
    private val reAcc = Regex("wacc([bhw])")
    private val reAbs = Regex("wabs([bhw])")
    private val reAbsDiff = Regex("wabsdiff([bhw])")
    private val reTextrc = Regex("textrc([bhw])")

    private fun xlate(ins: Ins, prev: Ins?): Xl {
        val b = ins.base
        val o = if (ins.ops.isEmpty()) emptyList() else splitOps(ins.ops)
        if (b.startsWith("wldr") || b.startsWith("wstr")) return xlateMem(ins)
        reLogic.matchEntire(b)?.let { m ->
            val op = when (m.groupValues[1]) { "and" -> "vand"; "or" -> "vorr"; "xor" -> "veor"; else -> "vbic" }
            return Xl(listOf(T("$op ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        }
        reAddSub.matchEntire(b)?.let { m ->
            val s = sizeOf(m.groupValues[2][0]); val sat = m.groupValues[3]
            val op = if (sat.isEmpty()) "v${m.groupValues[1]}.i$s" else "vq${m.groupValues[1]}.${if (sat == "us") "u" else "s"}$s"
            return Xl(listOf(T("$op ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        }
        if (b == "wmulwl") return Xl(listOf(T("vmul.i32 ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        reMul.matchEntire(b)?.let { m ->
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
            if (m.groupValues[2] == "l") return Xl(listOf(T("vmul.i16 ${wr(d)}, ${wr(n)}, ${wr(mm)}")))
            val sh = if (m.groupValues[3].isNotEmpty()) "vrshrn.i32" else "vshrn.i32"
            return Xl(listOf(T("vmull.${m.groupValues[1]}16 q0, ${wr(n)}, ${wr(mm)}"), T("$sh ${wr(d)}, q0, #16")))
        }
        reMadd.matchEntire(b)?.let { m ->
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
            return Xl(listOf(T("vmull.${m.groupValues[1]}16 q0, ${wr(n)}, ${wr(mm)}"), T("vpadd.i32 ${wr(d)}, d0, d1")))
        }
        reMac.matchEntire(b)?.let { m ->
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2]); val sg = m.groupValues[1]
            val l = arrayListOf(T("vmull.${sg}16 q0, ${wr(n)}, ${wr(mm)}"), T("vpaddl.${sg}32 q0, q0"))
            if (m.groupValues[2].isNotEmpty()) l.add(T("vadd.i64 ${wr(d)}, d0, d1"))
            else { l.add(T("vadd.i64 d0, d0, d1")); l.add(T("vadd.i64 ${wr(d)}, ${wr(d)}, d0")) }
            return Xl(l)
        }
        if (b == "wshufh") {
            val d = wnum(o[0]); val n = wnum(o[1]); val im = imm(o[2])
            var lit = 0L
            for (i in 0 until 4) { val s = (im shr (2 * i)) and 3; lit = lit or ((2 * s).toLong() shl (16 * i)) or ((2 * s + 1).toLong() shl (16 * i + 8)) }
            return Xl(listOf(T("vldr d0, [pc, #@]"), T("vtbl.8 ${wr(d)}, {${wr(n)}}, d0")), lit)
        }
        reUnpack.matchEntire(b)?.let { m ->
            val ei = m.groupValues[1]; val lh = m.groupValues[2]; val u = m.groupValues[3]; val s = sizeOf(m.groupValues[4][0])
            if (ei == "e") {
                val d = wnum(o[0]); val n = wnum(o[1]); val sg = if (u == "u") "u" else "s"
                return Xl(listOf(T("vmovl.$sg$s q0, ${wr(n)}"), T("vmov ${wr(d)}, ${if (lh == "l") "d0" else "d1"}")))
            }
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
            return Xl(listOf(T("vmov d0, ${wr(n)}"), T("vmov d1, ${wr(mm)}"), T("vzip.$s d0, d1"), T("vmov ${wr(d)}, ${if (lh == "l") "d0" else "d1"}")))
        }
        rePack.matchEntire(b)?.let { m ->
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
            val src = when (m.groupValues[1]) { "h" -> "s16"; "w" -> "s32"; else -> "s64" }
            val op = if (m.groupValues[2] == "us") "vqmovun" else "vqmovn"
            return Xl(listOf(T("vmov d0, ${wr(n)}"), T("vmov d1, ${wr(mm)}"), T("$op.$src ${wr(d)}, q0")))
        }
        reShift.matchEntire(b)?.let { m ->
            if (o.size == 3 && o[2].startsWith("#")) {
                val kind = m.groupValues[1]; val s = sizeOf(m.groupValues[2][0])
                val d = wnum(o[0]); val n = wnum(o[1]); var im = imm(o[2])
                val dd = wr(d); val nn = wr(n)
                if (kind == "ror") {
                    im %= s
                    if (im == 0) return Xl(listOf(T("vmov $dd, $nn")))
                    return Xl(listOf(T("vshr.u$s d0, $nn, #$im"), T("vsli.$s d0, $nn, #${s - im}"), T("vmov $dd, d0")))
                }
                if (im == 0) return Xl(listOf(T("vmov $dd, $nn")))
                if (kind == "srl") {
                    if (im >= s) return Xl(listOf(T("vmov.i32 $dd, #0")))
                    return Xl(listOf(T("vshr.u$s $dd, $nn, #$im")))
                }
                if (kind == "sra") return Xl(listOf(T("vshr.s$s $dd, $nn, #${minOf(im, s)}")))
                if (im >= s) return Xl(listOf(T("vmov.i32 $dd, #0")))
                return Xl(listOf(T("vshl.i$s $dd, $nn, #$im")))
            }
        }
        reShift.matchEntire(b)?.let { m ->
            if (o.size == 3 && o[2].startsWith("wr")) {                      // the count is in the low bits of a data register
                val kind = m.groupValues[1]; val sz = sizeOf(m.groupValues[2][0])
                if (kind == "ror") throw IwmmxtUnsupported("register rotate")
                val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
                // count = min(wRm, 64), so that a count of the lane size or more shifts everything out (a NEON count is a signed byte)
                val cl = listOf(T("vmov d0, ${wr(mm)}"), T("vmov.i32 d1, #0"), T("vqmovn.u64 d2, q0"), T("vmov.i32 d1, #64"), T("vmin.u32 d2, d2, d1"))
                val cnt = cl + (if (sz == 64) (if (kind == "sll") listOf(T("vmov d0, d2")) else listOf(T("vneg.s32 d0, d2")))
                    else (listOf(T("vdup.$sz d0, d2[0]")) + (if (kind == "sll") emptyList() else listOf(T("vneg.s$sz d0, d0")))))
                return Xl(cnt + listOf(T("vshl.${if (kind == "sra") "s" else "u"}$sz ${wr(d)}, ${wr(n)}, d0")))
            }
        }
        reMulW.matchEntire(b)?.let { m ->
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
            val sh = if (m.groupValues[2].isNotEmpty()) "vrshrn.i64" else "vshrn.i64"
            return Xl(listOf(T("vmull.${m.groupValues[1]}32 q0, ${wr(n)}, ${wr(mm)}"), T("$sh ${wr(d)}, q0, #32")))
        }
        reAvg2.matchEntire(b)?.let { m ->
            val sz = if (m.groupValues[1] == "b") 8 else 16
            return Xl(listOf(T("${if (m.groupValues[2].isNotEmpty()) "vrhadd" else "vhadd"}.u$sz ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        }
        reSad.matchEntire(b)?.let { m ->
            val d = wr(wnum(o[0])); val n = wr(wnum(o[1])); val mm = wr(wnum(o[2]))
            val l = ArrayList<T>()
            if (m.groupValues[1] == "b") { l.add(T("vabd.u8 d0, $n, $mm")); l.add(T("vpaddl.u8 d0, d0")); l.add(T("vpaddl.u16 d0, d0")); l.add(T("vpaddl.u32 d0, d0")) }
            else { l.add(T("vabd.u16 d0, $n, $mm")); l.add(T("vpaddl.u16 d0, d0")); l.add(T("vpaddl.u32 d0, d0")) }
            if (m.groupValues[2].isEmpty()) {                                  // wsadb/wsadh add to the low 32 bits of wRd (the result is 32 bits wide)
                l.add(T("vshl.i64 d1, $d, #32")); l.add(T("vshr.u64 d1, d1, #32")); l.add(T("vadd.i64 d0, d0, d1"))
                l.add(T("vshl.i64 d0, d0, #32")); l.add(T("vshr.u64 d0, d0, #32"))
            }
            l.add(T("vmov $d, d0"))
            return Xl(l)
        }
        reMovmsk.matchEntire(b)?.let { m ->
            val x = wr(wnum(o[1]))
            val l = when (m.groupValues[1]) {
                "b" -> listOf("vshr.u8 d0, $x, #7", "vsra.u16 d0, d0, #7", "vsra.u32 d0, d0, #14", "vsra.u64 d0, d0, #28")
                "h" -> listOf("vshr.u16 d0, $x, #15", "vsra.u32 d0, d0, #15", "vsra.u64 d0, d0, #30")
                else -> listOf("vshr.u32 d0, $x, #31", "vsra.u64 d0, d0, #31")
            }
            return Xl(l.map { T(it) } + T("vmov.u8 ${o[0]}, d0[0]"))
        }
        reTmia.matchEntire(b)?.let { m ->
            val d = wr(wnum(o[0])); val rm = o[1]; val rs = o[2]
            val kind = m.groupValues[1]
            val l = ArrayList<T>()
            fun half(dst: String, r: String, top: Boolean) { l.add(T("vdup.32 $dst, $r")); if (!top) l.add(T("vshl.i32 $dst, $dst, #16")); l.add(T("vshr.s32 $dst, $dst, #16")) }
            when (kind) {
                "" -> { l.add(T("vdup.32 d0, $rm")); l.add(T("vdup.32 d1, $rs")); l.add(T("vmull.s32 q1, d0, d1")) }
                "ph" -> { l.add(T("vdup.32 d0, $rm")); l.add(T("vdup.32 d1, $rs")); l.add(T("vmull.s16 q1, d0, d1")); l.add(T("vpaddl.s32 d2, d2")) }
                else -> { half("d0", rm, kind[0] == 't'); half("d1", rs, kind[1] == 't'); l.add(T("vmull.s32 q1, d0, d1")) }
            }
            l.add(T("vadd.i64 $d, $d, d2"))
            return Xl(l)
        }
        reAcc.matchEntire(b)?.let { m ->
            val d = wr(wnum(o[0])); val n = wr(wnum(o[1]))
            return Xl(when (m.groupValues[1]) {
                "b" -> listOf(T("vpaddl.u8 d0, $n"), T("vpaddl.u16 d0, d0"), T("vpaddl.u32 $d, d0"))
                "h" -> listOf(T("vpaddl.u16 d0, $n"), T("vpaddl.u32 $d, d0"))
                else -> listOf(T("vpaddl.u32 $d, $n"))
            })
        }
        reAbs.matchEntire(b)?.let { m -> return Xl(listOf(T("vabs.s${sizeOf(m.groupValues[1][0])} ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}"))) }
        reAbsDiff.matchEntire(b)?.let { m -> return Xl(listOf(T("vabd.u${sizeOf(m.groupValues[1][0])} ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}"))) }
        reTextrc.matchEntire(b)?.let { m ->
            // flags of lane #i of the preceding producer into the CPSR: N is the top bit of the lane, Z its being zero
            val layout = if (prev == null) null else layoutOf(prev.base)
            if (prev == null || layout == null) throw IwmmxtUnsupported("flag producer for $b")
            val s = sizeOf(m.groupValues[1][0]); val i = imm(o[1]); val x = wr(wnum(splitOps(prev.ops)[0])); val tmp = "r12"
            val ext = if (s == 32) "vmov.32 $tmp, $x[$i]" else "vmov.u$s $tmp, $x[$i]"
            return Xl(listOf(T("str $tmp, [sp, #-4]!"), T(ext), T("lsls $tmp, $tmp, #${32 - s}"), T("ldr $tmp, [sp], #4")))
        }
        reShiftG.matchEntire(b)?.let { m ->
            val kind = m.groupValues[1]; val s = sizeOf(m.groupValues[2][0])
            val d = wnum(o[0]); val n = wnum(o[1]); val k = (reWcgr.matchEntire(o[2]) ?: throw IwmmxtUnsupported("wcgr ${o[2]}")).groupValues[1].toInt()
            if (kind == "srl") return Xl(listOf(T("vshl.u$s ${wr(d)}, ${wr(n)}, ${cgr(k)}")))
            if (kind == "sra") return Xl(listOf(T("vshl.s$s ${wr(d)}, ${wr(n)}, ${cgr(k)}")))
            return Xl(listOf(T("vneg.s16 d0, ${cgr(k)}"), T("vshl.u$s ${wr(d)}, ${wr(n)}, d0")))
        }
        reMinMax.matchEntire(b)?.let { m ->
            return Xl(listOf(T("v${m.groupValues[1]}.${m.groupValues[2]}${sizeOf(m.groupValues[3][0])} ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        }
        reCmpGt.matchEntire(b)?.let { m ->
            return Xl(listOf(T("vcgt.${m.groupValues[1]}${sizeOf(m.groupValues[2][0])} ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        }
        reCmpEq.matchEntire(b)?.let { m ->
            return Xl(listOf(T("vceq.i${sizeOf(m.groupValues[1][0])} ${wr(wnum(o[0]))}, ${wr(wnum(o[1]))}, ${wr(wnum(o[2]))}")))
        }
        reAlignR.matchEntire(b)?.let { m ->
            val k = m.groupValues[1].toInt(); val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2])
            return Xl(listOf(T("vneg.s16 d0, ${cgr(k)}"), T("vshl.i16 d0, d0, #3"), T("vmov.i16 d1, #0x38"), T("vand d0, d0, d1"),
                T("vneg.s16 d1, d0"), T("vshl.u64 d2, ${wr(n)}, d1"), T("vmov.i16 d3, #64"), T("vsub.i16 d3, d3, d0"),
                T("vshl.u64 d3, ${wr(mm)}, d3"), T("vorr ${wr(d)}, d2, d3")))
        }
        if (b == "waligni") {
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2]); val im = imm(o[3])
            if (im == 0) return Xl(listOf(T("vmov ${wr(d)}, ${wr(n)}")))
            return Xl(listOf(T("vext.8 ${wr(d)}, ${wr(n)}, ${wr(mm)}, #$im")))
        }
        if (b == "wmerge") {
            val d = wnum(o[0]); val n = wnum(o[1]); val mm = wnum(o[2]); val im = imm(o[3]); val sh = 8 * im
            if (im == 0) return Xl(listOf(T("vmov ${wr(d)}, ${wr(mm)}")))
            if (d == n) return Xl(listOf(T("vsli.64 ${wr(d)}, ${wr(mm)}, #$sh")))
            return Xl(listOf(T("vmov d0, ${wr(n)}"), T("vsli.64 d0, ${wr(mm)}, #$sh"), T("vmov ${wr(d)}, d0")))
        }
        reBcst.matchEntire(b)?.let { m -> return Xl(listOf(T("vdup{c}.${sizeOf(m.groupValues[1][0])} ${wr(wnum(o[0]))}, ${o[1]}", true))) }
        reExtr.matchEntire(b)?.let { m ->
            val s = sizeOf(m.groupValues[2][0]); val i = imm(o[2])
            val ty = if (s == 32) "32" else "${m.groupValues[1]}$s"
            return Xl(listOf(T("vmov{c}.$ty ${o[0]}, ${wr(wnum(o[1]))}[$i]", true)))
        }
        reInsr.matchEntire(b)?.let { m ->
            return Xl(listOf(T("vmov{c}.${sizeOf(m.groupValues[1][0])} ${wr(wnum(o[0]))}[${imm(o[2])}], ${o[1]}", true)))
        }
        if (b == "tmcrr") return Xl(listOf(T("vmov{c} ${wr(wnum(o[0]))}, ${o[1]}, ${o[2]}", true)))
        if (b == "tmrrc") return Xl(listOf(T("vmov{c} ${o[0]}, ${o[1]}, ${wr(wnum(o[2]))}", true)))
        if (b == "tmcr") {
            val k = (reWcgr.matchEntire(o[0]) ?: throw IwmmxtUnsupported("wcgr ${o[0]}")).groupValues[1].toInt()
            return Xl(listOf(T("vdup.16 ${cgr(k)}, ${o[1]}"), T("vneg.s16 ${cgr(k)}, ${cgr(k)}")))
        }
        if (b == "tmrc") {
            val k = (reWcgr.matchEntire(o[1]) ?: throw IwmmxtUnsupported("wcgr ${o[1]}")).groupValues[1].toInt()
            return Xl(listOf(T("vneg.s16 d0, ${cgr(k)}"), T("vmov.u16 ${o[0]}, d0[0]")))
        }
        reFlagC.matchEntire(b)?.let { m ->
            if (o != listOf("pc")) throw IwmmxtUnsupported("tandc/torc operand")
            val layout = if (prev == null) null else layoutOf(prev.base)
            if (prev == null || layout == null) throw IwmmxtUnsupported("flag producer for $b")
            val x = wr(wnum(splitOps(prev.ops)[0]))
            val tmp = "r12"
            val l = ArrayList<T>()
            l.add(T("str $tmp, [sp, #-4]!"))
            for (s in flagLines(layout, x, if (m.groupValues[1] == "and") "and" else "or", tmp)) l.add(T(s))
            l.add(T("ldr $tmp, [sp], #4"))
            return Xl(l)
        }
        throw IwmmxtUnsupported("mnemonic $b")
    }

    // ------------------------------------------------------------------------------------------------ control flow
    private class Flow(var targets: IntArray, var fall: Boolean, var why: String?)

    private fun armFlow(w: Int, addr: Int): Flow {
        val cc = w ushr 28
        if (cc != 0xf && (w and 0x0e000000) == 0x0a000000) {
            var off = w and 0xffffff; if (off and 0x800000 != 0) off -= 1 shl 24
            val t = addr + 8 + (off shl 2)
            return if (w and 0x01000000 != 0) Flow(intArrayOf(t), true, null) else Flow(intArrayOf(t), cc != 0xe, null)
        }
        if (cc == 0xf && (w and -0x2000000) == -0x6000000) return Flow(IntArray(0), true, null)          // blx imm: Thumb
        if ((w and 0x0ffffff0) == 0x012fff10 || (w and 0x0ffffff0) == 0x012fff30) return Flow(IntArray(0), cc != 0xe || (w and 0x20) != 0, null)
        val rd = (w ushr 12) and 0xf
        if (cc != 0xf && (w and 0x0c000000) == 0 && rd == 15 && (w and 0x01900000) != 0x01000000) {
            if ((w and 0x0fffffff) == 0x01a0f00e || ((w and 0x0ffff000) == 0x01a0f000 && (w and 0xff0) == 0)) return Flow(IntArray(0), cc != 0xe, null)
            return Flow(IntArray(0), cc != 0xe, "computed jump")
        }
        if (cc != 0xf && (w and 0x0c100000) == 0x04100000 && rd == 15) {
            if ((w and 0x0f7f0000) == 0x041d0000) return Flow(IntArray(0), cc != 0xe, null)           // ldr pc, [sp], #n: pop {pc}
            return Flow(IntArray(0), cc != 0xe, "ldr pc")
        }
        if (cc != 0xf && (w and 0x0e108000) == 0x08108000) return Flow(IntArray(0), cc != 0xe, null)
        return Flow(IntArray(0), true, null)
    }

    /**
     * The targets of a switch table: `add pc, pc, rN, lsl #2` followed by one `b` per case, or `ldr pc, [pc, rN, lsl #2]` followed by one
     * absolute address per case (the load address of a prelinked library is [base], 0 otherwise). null: not one of these two forms.
     * A table can have more entries than the switch uses; the surplus is code that is never reached, which is harmless to translate.
     */
    private fun jumpTable(d: ByteArray, a: Int, w: Int, lo: Int, hi: Int, base: Int): IntArray? {
        val kind = when (w and 0x0ffffff0) { 0x008ff100 -> 0; 0x079ff100 -> 1; else -> return null }
        if (w ushr 28 == 0xf) return null
        val out = ArrayList<Int>()
        var p = a + 8
        while (p + 4 <= hi && out.size < 4096) {
            val e = u32(d, p)
            if (kind == 0) { if (e ushr 28 == 0xf || (e and 0x0f000000) != 0x0a000000) break; out.add(p) }
            else {
                val t = e - base
                if (t < lo || t >= hi || t and 1 != 0) break
                out.add(t and 3.inv())
            }
            p += 4
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    /** A word that cannot be an ARM instruction of this firmware (unconditional space other than blx/pld and the iWMMXt2 forms). */
    private fun notCode(w: Int): Boolean {
        if (w ushr 28 != 0xf) return false
        if ((w and -0x2000000) == -0x6000000) return false                      // blx imm
        if ((w and 0xff000000.toInt()) == 0xf5000000.toInt() || (w and 0xff000000.toInt()) == 0xf7000000.toInt()) return false   // pld
        return decode(0, w) == null                                              // an iWMMXt2 word of the unconditional space is code
    }

    /**
     * What is code, reached from [trusted] entry points (function symbols) and [spec]ulative ones (untyped symbols, which can name a
     * table in .text, and every word of the library that looks like a code address: switch tables, vtables, callbacks). A speculative
     * walk that meets something that cannot be code or a jump it cannot follow is thrown away as data; one that ends in a return or a
     * jump is kept. [warn] gets the computed jumps of the trusted walks that no table could be found for.
     */
    private fun reach(d: ByteArray, trusted: List<Int>, spec: List<Int>, lo: Int, hi: Int, warn: MutableList<Int>, base: Int = 0): BooleanArray {
        val n = (hi - lo) / 4 + 1
        val seen = BooleanArray(n)
        val tmp = BooleanArray(n)
        fun walk(start: List<Int>, mark: BooleanArray, trail: ArrayList<Int>?, w: MutableList<Int>): Boolean {
            val work = ArrayList<Int>(start)
            while (work.isNotEmpty()) {
                var a = work.removeAt(work.size - 1)
                while (a >= lo && a + 4 <= hi && a and 3 == 0 && !mark[(a - lo) / 4] && (trail == null || !seen[(a - lo) / 4])) {
                    mark[(a - lo) / 4] = true; trail?.add((a - lo) / 4)
                    val word = u32(d, a)
                    if (trail != null && notCode(word)) return false
                    val f = armFlow(word, a)
                    var ft = f.fall
                    // mov lr,pc ; ldr pc,... / mov pc,... is an indirect call: execution continues after it
                    if (a >= 4 && u32(d, a - 4) == -0x1e5f1ff1 && ((word ushr 12) and 0xf) == 15 && f.targets.isEmpty()) { ft = true; f.why = null }
                    if (f.why != null) jumpTable(d, a, word, lo, hi, base)?.let { f.targets = it; f.why = null }
                    if (f.why != null) { if (trail != null) return false; w.add(a) }
                    for (t in f.targets) if (t >= lo && t + 4 <= hi && !mark[(t - lo) / 4] && (trail == null || !seen[(t - lo) / 4])) work.add(t)
                    if (!ft) break
                    a += 4
                }
            }
            return true
        }
        walk(trusted, seen, null, warn)
        for (s0 in spec) {
            if (s0 < lo || s0 + 4 > hi || seen[(s0 - lo) / 4]) continue
            val trail = ArrayList<Int>()
            val ok = walk(listOf(s0), tmp, trail, ArrayList())
            for (i in trail) { tmp[i] = false; if (ok) seen[i] = true }
        }
        return seen
    }

    // ------------------------------------------------------------------------------------------------ patcher
    private class Plan(val addr: Int, val cc: Int, val inPlace: Int?, val cave: ByteArray?, val markerOff: Int)

    private class Patcher(val orig: ByteArray) {
        fun run(): Result? {
            val elf = Elf(orig)
            val text = elf.secs.firstOrNull { it.name == ".text" }
            val exec = elf.segs.firstOrNull { it.type == 1 && it.flags and 1 != 0 } ?: throw IwmmxtUnsupported("no executable segment")
            val lo = if (text != null) text.addr else exec.vaddr
            val hi = if (text != null) text.addr + text.size else exec.vaddr + exec.filesz
            if (lo < 0 || hi > orig.size || lo >= hi) throw IwmmxtUnsupported("odd .text bounds")
            // ARM entry points: function symbols are trusted; untyped ones (hand-written assembly, but also tables placed in .text) and every
            // word that looks like a code address (switch tables, vtables, callbacks) are followed only while they stay code
            val trusted = ArrayList<Int>(); val spec = ArrayList<Int>()
            for (s in elf.secs) {
                if (s.type != 11 && s.type != 2) continue
                var o = s.off
                while (o + 16 <= s.off + s.size) {
                    val value = u32(orig, o + 4); val info = orig[o + 12].toInt() and 0xf; val shndx = u16(orig, o + 14)
                    if (shndx != 0 && shndx < 0xff00 && value != 0 && value and 1 == 0 && (info == 0 || info == 2) && shndx < elf.secs.size &&
                        elf.secs[shndx].flags and 4 != 0 && value >= lo && value < hi) (if (info == 2) trusted else spec).add(value)
                    o += 16
                }
            }
            val base = if (hasPrelinkTag(orig)) u32(orig, orig.size - 8) else 0
            val ptr = BooleanArray((hi - lo) / 4 + 1)
            for (sg in elf.segs) {
                if (sg.type != 1) continue
                var o = sg.off
                while (o + 4 <= sg.off + sg.filesz && o + 4 <= orig.size) {
                    val v = u32(orig, o) - base
                    if (v >= lo && v < hi && v and 3 == 0) ptr[(v - lo) / 4] = true
                    o += 4
                }
            }
            for (i in ptr.indices) if (ptr[i]) spec.add(lo + i * 4)
            val warn = ArrayList<Int>()
            val seen = reach(orig, trusted.distinct().sorted(), spec.distinct().sorted(), lo, hi, warn, base)
            val cand = ArrayList<Int>()
            for (i in seen.indices) if (seen[i]) { val a = lo + i * 4; if (isCandidate(u32(orig, a))) cand.add(a) }
            if (cand.isEmpty()) return null
            val cLo = cand.first(); val cHi = cand.last() + 4
            val jumps = warn.count { it in cLo until cHi }

            var maxEnd = 0
            for (sg in elf.segs) if (sg.type == 1) maxEnd = maxOf(maxEnd, sg.vaddr + sg.memsz)
            if (maxEnd == 0) throw IwmmxtUnsupported("no PT_LOAD")
            val baseV = (maxEnd + 0xfff) and 0xfff.inv()
            val out = orig.copyOf()
            val blob = java.io.ByteArrayOutputStream()
            var inPlace = 0; var caves = 0; var count = 0; var skipped = 0
            val problems = LinkedHashMap<String, IntArray>()           // what cannot be translated -> [count, first address]
            fun problem(key: String, a: Int) { val e = problems.getOrPut(key) { intArrayOf(0, a) }; e[0]++ }
            for (a in cand) {
                val w = u32(orig, a)
                val ins = decode(a, w)
                if (ins == null) {
                    // a load/store (c/d) that is no valid wldr/wstr form is data that the walk ran into; a data-processing word (e) is a real instruction we lack
                    if (((w ushr 24) and 0xf) != 0xe || (w ushr 28) != 0xe && (w ushr 28) != 0xf) { skipped++; continue }
                    problem("undecodable coprocessor instruction 0x${Integer.toHexString(w)}", a); continue
                }
                var prev: Ins? = null
                if (ins.base.startsWith("tandc") || ins.base.startsWith("torc") || ins.base.startsWith("textrc")) {
                    for (k in 1..12) {                                      // wCASF keeps the last producer's flags across the branches in between
                        val pa = a - 4 * k; if (pa < lo) break
                        val pw = u32(orig, pa)
                        if (!isCandidate(pw)) continue
                        val pi = decode(pa, pw) ?: continue
                        if (DATA_NONPROD.none { pi.base.startsWith(it) }) { prev = pi; break }
                    }
                }
                val xl = try { xlate(ins, prev) } catch (e: IwmmxtUnsupported) { problem("${ins.base}: ${e.message}", a); continue }
                count++
                val condSfx = if (ins.cc < 14) ins.cc else 14
                if (xl.lines.size == 1 && xl.literal == null && (ins.cc == 14 || ins.cc == 15 || xl.lines[0].vfp)) {
                    val enc = try { Asm.encode(xl.lines[0].text.replace("{c}", ""), condSfx) } catch (e: IwmmxtUnsupported) { problem("${ins.base}: ${e.message}", a); continue }
                    put32(out, a, enc); inPlace++
                } else {
                    while (blob.size() % 8 != 0) blob.write(0)
                    val caddr = baseV + blob.size()
                    val n = xl.lines.size
                    val body = ByteArray(4 * (n + 1) + (if (xl.literal != null) 8 else 0))
                    for ((i, t) in xl.lines.withIndex()) {
                        var txt = t.text.replace("{c}", "")
                        if (txt.contains("#@")) txt = txt.replace("#@", "#" + ((n + 1) * 4 - (i * 4 + 8)))
                        put32(body, 4 * i, try { Asm.encode(txt) } catch (e: IwmmxtUnsupported) { problem("${ins.base}: ${e.message}", a); 0 })
                    }
                    put32(body, 4 * n, branch(14, caddr + 4 * n, a + 4))
                    if (xl.literal != null) { put32(body, 4 * n + 4, xl.literal.toInt()); put32(body, 4 * n + 8, (xl.literal ushr 32).toInt()) }
                    blob.write(body)
                    put32(out, a, branch(if (ins.cc < 14) ins.cc else 14, a, caddr)); caves++
                }
            }
            if (count == 0 && problems.isEmpty()) return null
            if (problems.isNotEmpty()) {
                val shown = problems.entries.sortedByDescending { it.value[0] }.take(6).joinToString("; ") { "${it.key} x${it.value[0]} (first at 0x${Integer.toHexString(it.value[1])})" }
                throw IwmmxtUnsupported("${problems.values.sumOf { it[0] }} instructions cannot be translated: $shown" + if (problems.size > 6) "; ${problems.size - 6} more kinds" else "")
            }
            val bytes = blob.toByteArray()
            // everything was patched in place: no cave, so no new segment (an empty PT_LOAD makes the 2.x linker fail with
            // "failed to map segment ... (0x00000000)"), and the file, prelink tag included, keeps its size
            if (bytes.isEmpty()) return Result(out, count, inPlace, 0, 0, skipped, jumps)
            // new PT_LOAD: convert PT_GNU_STACK / PT_NOTE
            val slot = elf.segs.firstOrNull { it.type == 0x6474e551 || it.type == 4 } ?: throw IwmmxtUnsupported("no spare program header")
            var total = out.size
            val pad = (0x1000 - (total and 0xfff)) and 0xfff
            val tail = if (hasPrelinkTag(orig)) 8 else 0                   // <load address> + "PRE ": must stay the last 8 bytes of the file
            val result = out.copyOf(total + pad + bytes.size + tail)
            System.arraycopy(bytes, 0, result, total + pad, bytes.size)
            if (tail != 0) System.arraycopy(orig, orig.size - 8, result, total + pad + bytes.size, 8)
            val foff = total + pad
            val po = elf.phoff + slot.index * elf.phentsize
            put32(result, po, 1); put32(result, po + 4, foff); put32(result, po + 8, baseV); put32(result, po + 12, baseV)
            put32(result, po + 16, bytes.size); put32(result, po + 20, bytes.size); put32(result, po + 24, CAVE_FLAGS); put32(result, po + 28, 0x1000)
            return Result(result, count, inPlace, caves, bytes.size, skipped, jumps)
        }

        private fun branch(cc: Int, src: Int, dst: Int): Int {
            val off = (dst - (src + 8)) shr 2
            if (off < -(1 shl 23) || off >= (1 shl 23)) throw IwmmxtUnsupported("branch out of range")
            return (cc shl 28) or 0x0a000000 or (off and 0xffffff)
        }
    }

    /**
     * p_flags of the cave segment: R+W+X, deliberately writable. The cave lies after the library's own data, and the 2.x linker
     * mprotects ONE span, from the lowest to the highest end of all the non-writable PT_LOADs, to R+X once it has relocated
     * the library. With an R+X cave the span covered .data/.bss, every later store to a global faulted (zygote in the
     * constructors of libskia, mediaserver in those of libcamera). A writable segment is left out of that span.
     */
    internal const val CAVE_FLAGS = 7

    // exposed for tests
    internal fun assemble(line: String): Int = Asm.encode(line)
    internal fun decodeWord(w: Int): String? = decode(0, w)?.let { it.base + (if (it.cc in 0..13) CC[it.cc] else "") + " " + it.ops }
    private val CC = arrayOf("eq", "ne", "cs", "cc", "mi", "pl", "vs", "vc", "hi", "ls", "ge", "lt", "gt", "le")
}
