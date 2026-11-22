package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How the guest's own libc reads a legacy (version 0x45434f76) property area.
 *
 * Classic bionic (Android 1.0 - 4.4 and most device images): prop_info is { char name[32]; uint serial; char value[92] },
 * 128 bytes, serial at offset 32. The Android-x86 5.1 libc (and possibly other PC builds) keeps the legacy reader but
 * with { char name[128]; serial; value[92] }: serial at 0x80, value at 0x84, 224 bytes. An area written in the classic
 * layout makes that libc read the next record's name as a length byte ("ro.k" = 0x6b = 107 bytes), property_get()
 * overflows its 92-byte buffer and the stack protector aborts zygote in set_process_name.
 *
 * Added for AEmulator Plus (2026-11-20). The layout is read from the code, not guessed from the Android version:
 * [serialOffset] finds __system_property_read_compat (the .symtab symbol, or, when the library is stripped, the
 * callee of __system_property_read) and looks for the two displacements it must contain, "mov r,[r+S]" for the
 * serial and "add/lea r,S+4" for the start of the value. x86-32 only; null for anything it cannot confirm, and the
 * caller then keeps the classic layout.
 */
object LibcPropLayout {
    const val CLASSIC_NAME_MAX = 32

    private const val MIN_SERIAL = 0x20
    private const val MAX_SERIAL = 0x200

    /** Serial offset (= name field size) of the legacy reader in [libc], or null when it cannot be determined. */
    fun serialOffset(libc: File): Int? =
        runCatching { if (libc.isFile && libc.length() in 1..(64L shl 20)) serialOffset(libc.readBytes()) else null }.getOrNull()

    fun serialOffset(d: ByteArray): Int? = runCatching { detect(d) }.getOrNull()

    /** The guest's name field size for a tree's x86 libc ([CLASSIC_NAME_MAX] when it is the classic layout or unknown). */
    fun nameMaxFor(root: File): Int =
        serialOffset(File(root, "system/lib/libc.so"))?.takeIf { it != CLASSIC_NAME_MAX } ?: CLASSIC_NAME_MAX

    private class Section(val type: Int, val addr: Int, val off: Int, val size: Int, val link: Int)
    private class Sym(val value: Int, val size: Int)

    private fun detect(d: ByteArray): Int? {
        if (d.size < 52 || d[0] != 0x7f.toByte() || d[1] != 'E'.code.toByte() || d[2] != 'L'.code.toByte() || d[3] != 'F'.code.toByte()) return null
        if (d[4].toInt() != 1 || d[5].toInt() != 1) return null // ELF32, little endian
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(18).toInt() and 0xffff) != 3) return null // EM_386
        val shoff = b.getInt(32)
        val shentsize = b.getShort(46).toInt() and 0xffff
        val shnum = b.getShort(48).toInt() and 0xffff
        if (shoff <= 0 || shentsize < 40 || shnum == 0 || shoff.toLong() + shnum.toLong() * shentsize > d.size) return null
        val secs = List(shnum) {
            val o = shoff + it * shentsize
            Section(b.getInt(o + 4), b.getInt(o + 12), b.getInt(o + 16), b.getInt(o + 20), b.getInt(o + 24))
        }
        val syms = HashMap<String, Sym>()
        for (s in secs) {
            if (s.type != 2 && s.type != 11) continue // SHT_SYMTAB, SHT_DYNSYM
            val str = secs.getOrNull(s.link) ?: continue
            var o = s.off
            while (o + 16 <= s.off + s.size && o + 16 <= d.size) {
                val nm = b.getInt(o); val value = b.getInt(o + 4); val size = b.getInt(o + 8)
                val shndx = b.getShort(o + 14).toInt() and 0xffff
                o += 16
                if (shndx == 0 || size <= 0) continue
                val name = cstr(d, str.off + nm, str.off + str.size) ?: continue
                if (name == "__system_property_read" || name == "__system_property_read_compat") syms[name] = Sym(value, size)
            }
        }
        // function bodies that can hold the legacy reader: the symbol, else every callee of __system_property_read
        val bodies = ArrayList<IntRange>()
        fun va2off(va: Int): Int {
            for (s in secs) if (s.addr != 0 && s.type != 8 && va >= s.addr && va < s.addr + s.size) return va - s.addr + s.off
            return -1
        }
        syms["__system_property_read_compat"]?.let { c ->
            val o = va2off(c.value)
            if (o >= 0) bodies += o until minOf(d.size, o + maxOf(c.size, 0x80))
        }
        if (bodies.isEmpty()) {
            val r = syms["__system_property_read"] ?: return null
            val ro = va2off(r.value)
            if (ro < 0) return null
            for (i in ro until minOf(d.size - 5, ro + r.size)) {
                if (d[i] != 0xe8.toByte()) continue
                val target = r.value + (i - ro) + 5 + b.getInt(i + 1)
                val to = va2off(target)
                if (to >= 0 && to != ro) bodies += to until minOf(d.size, to + 0x200)
            }
        }
        for (body in bodies) {
            val loads = HashSet<Int>(); val adds = HashSet<Int>()
            scan(d, b, body.first, body.last + 1, loads, adds)
            val s = loads.filter { it in MIN_SERIAL..MAX_SERIAL && it % 4 == 0 && (it + 4) in adds }.minOrNull()
            if (s != null) return s
        }
        return null
    }

    /** Displacements of "mov r,[r+d]" (loads) and of "add/sub r,imm", "lea r,[r+d]" (adds) in a code range; SIB forms are skipped. */
    private fun scan(d: ByteArray, b: ByteBuffer, from: Int, to: Int, loads: MutableSet<Int>, adds: MutableSet<Int>) {
        var i = from
        while (i < to - 6) {
            val op = d[i].toInt() and 0xff
            when (op) {
                0x8b, 0x8d -> {
                    val m = d[i + 1].toInt() and 0xff
                    val mod = m shr 6; val rm = m and 7
                    if (rm != 4) {
                        val disp = when (mod) { 1 -> d[i + 2].toInt(); 2 -> b.getInt(i + 2); else -> null }
                        if (disp != null) (if (op == 0x8b) loads else adds).add(disp)
                    }
                }
                0x05 -> adds.add(b.getInt(i + 1))
                0x2d -> adds.add(-b.getInt(i + 1))
                0x81, 0x83 -> {
                    val m = d[i + 1].toInt() and 0xff
                    val r = (m shr 3) and 7
                    if ((m shr 6) == 3 && (r == 0 || r == 5)) {
                        val v = if (op == 0x81) b.getInt(i + 2) else d[i + 2].toInt()
                        adds.add(if (r == 0) v else -v)
                    }
                }
            }
            i++
        }
    }

    private fun cstr(d: ByteArray, from: Int, limit: Int): String? {
        if (from < 0 || from >= d.size) return null
        var e = from
        val end = minOf(limit, d.size)
        while (e < end && d[e] != 0.toByte()) e++
        return if (e >= end) null else String(d, from, e - from, Charsets.ISO_8859_1)
    }
}
