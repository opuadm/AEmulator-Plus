package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IPCThreadState::attemptIncStrongHandle of Android 1.x..4.x libbinder in a minimal ELF32 ARM file, and the patch that makes
 * it fail at once: the stand's binder emulation does not know BC_ATTEMPT_ACQUIRE (0x4008630a), drops it and the caller waits
 * for BR_ACQUIRE_RESULT for ever (Samsung GT-I7500, Android 1.5: AudioFlinger's thread, then AudioService.<init>).
 */
class BinderAttemptAcquireTest {
    private val fnOff = 0x100
    private val next = intArrayOf(0xE92D4010.toInt(), 0xE1A04000.toInt(), 0xE3A07001.toInt(), 0xEF000000.toInt()) // the function behind it

    /** a body in the shape of the real one: it ends in a literal pool, and the first literal is BC_ATTEMPT_ACQUIRE */
    private val body = intArrayOf(
        0xE92D4070.toInt(), 0xE1A04000.toInt(), 0xE59F5020.toInt(), 0xE1A06001.toInt(), // push {r4-r6, lr} ; mov r4,r0 ; ldr r5,=cmd ; mov r6,r1
        0xE28D0004.toInt(), 0xE5835000.toInt(), 0xE1A00004.toInt(), 0xE8BD8070.toInt(), // stand-ins for the Parcel writes, pop {r4-r6, pc}
        0x4008630A, 0x00000000
    )
    private val bodyWithoutCommand = intArrayOf(
        0xE92D4070.toInt(), 0xE1A04000.toInt(), 0xE3E00025.toInt() xor 0x00000001, 0xE1A06001.toInt(),
        0xE28D0004.toInt(), 0xE5835000.toInt(), 0xE1A00004.toInt(), 0xE8BD8070.toInt(), 0x12345678, 0x00000000
    )

    /** ELF32 ARM with [name] (a dynamic symbol) at [fnOff]; thumb sets bit 0 of its address. */
    private fun elf(code: IntArray, thumb: Boolean = false, name: String = ElfPatch.ATTEMPT_ACQUIRE_FN): ByteArray {
        val d = ByteArray(0x400)
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        d[0] = 0x7f; d[1] = 'E'.code.toByte(); d[2] = 'L'.code.toByte(); d[3] = 'F'.code.toByte(); d[4] = 1; d[5] = 1; d[6] = 1
        b.putShort(16, 3); b.putShort(18, 40); b.putInt(20, 1)
        b.putInt(28, 52); b.putInt(32, 0x300)
        b.putShort(40, 52); b.putShort(42, 32); b.putShort(44, 1); b.putShort(46, 40); b.putShort(48, 3)
        // PT_LOAD of the whole file
        b.putInt(52, 1); b.putInt(52 + 4, 0); b.putInt(52 + 8, 0); b.putInt(52 + 12, 0)
        b.putInt(52 + 16, d.size); b.putInt(52 + 20, d.size); b.putInt(52 + 24, 5); b.putInt(52 + 28, 0x1000)
        var o = fnOff
        for (w in code) { b.putInt(o, w); o += 4 }
        for (w in next) { b.putInt(o, w); o += 4 }
        // .dynstr at 0x200: "\0<name>\0"
        val str = ("\u0000" + name + "\u0000").toByteArray(Charsets.US_ASCII)
        System.arraycopy(str, 0, d, 0x200, str.size)
        // .dynsym at 0x280: null symbol + the function (GLOBAL FUNC)
        b.putInt(0x290, 1); b.putInt(0x290 + 4, fnOff or if (thumb) 1 else 0); b.putInt(0x290 + 8, 64)
        d[0x290 + 12] = 0x12; b.putShort(0x290 + 14, 1)
        // section headers at 0x300: null, .dynsym (type 11, link 2), .dynstr
        val s1 = 0x300 + 40
        b.putInt(s1 + 4, 11); b.putInt(s1 + 16, 0x280); b.putInt(s1 + 20, 32); b.putInt(s1 + 24, 2); b.putInt(s1 + 36, 16)
        val s2 = 0x300 + 80
        b.putInt(s2 + 4, 3); b.putInt(s2 + 16, 0x200); b.putInt(s2 + 20, str.size)
        return d
    }

    private fun words(d: ByteArray, off: Int, n: Int): IntArray {
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(n) { b.getInt(off + 4 * it) }
    }

    private fun temp(d: ByteArray) = File.createTempFile("libbinder", ".so").apply { deleteOnExit(); writeBytes(d) }

    @Test fun armEntryReturnsInvalidOperationAndNothingElseChanges() {
        val orig = elf(body)
        val f = temp(orig)
        assertEquals(1, ElfPatch.attemptAcquireFails(f))
        val d = f.readBytes()
        // mvn r0,#37 ; bx lr  (r0 = ~37 = -38 = INVALID_OPERATION)
        assertEquals(0xE3E00025.toInt(), words(d, fnOff, 1)[0])
        assertEquals(0xE12FFF1E.toInt(), words(d, fnOff + 4, 1)[0])
        // the rest of the body, the literal and the next function are as they were
        assertArrayEquals(words(orig, fnOff + 8, body.size - 2 + next.size), words(d, fnOff + 8, body.size - 2 + next.size))
        assertEquals(orig.size, d.size)
    }

    @Test fun thumbEntryUsesThumbCode() {
        val f = temp(elf(body, thumb = true))
        assertEquals(1, ElfPatch.attemptAcquireFails(f))
        val d = f.readBytes()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x2025, b.getShort(fnOff).toInt() and 0xffff)      // movs r0, #37
        assertEquals(0x43C0, b.getShort(fnOff + 2).toInt() and 0xffff)  // mvns r0, r0
        assertEquals(0x4770, b.getShort(fnOff + 4).toInt() and 0xffff)  // bx lr
        assertEquals(6, ElfPatch.attemptAcquireBytes(true).size)
        assertEquals(8, ElfPatch.attemptAcquireBytes(false).size)
    }

    @Test fun patchedFunctionIsNotWrittenAgain() {
        val f = temp(elf(body))
        assertEquals(1, ElfPatch.attemptAcquireFails(f))
        val once = f.readBytes()
        assertEquals(0, ElfPatch.attemptAcquireFails(f))
        assertArrayEquals(once, f.readBytes())
    }

    @Test fun aLibbinderThatNoLongerSendsTheCommandIsLeftAlone() {
        val orig = elf(bodyWithoutCommand)
        val f = temp(orig)
        assertEquals(0, ElfPatch.attemptAcquireFails(f))
        assertArrayEquals(orig, f.readBytes())
    }

    @Test fun noSuchFunctionNothingToDo() {
        val orig = elf(body, name = "_ZN7android14IPCThreadState4selfEv")
        val f = temp(orig)
        assertEquals(0, ElfPatch.attemptAcquireFails(f))
        assertArrayEquals(orig, f.readBytes())
        assertEquals(0, ElfPatch.attemptAcquireFails(File("/nonexistent/libbinder.so")))
    }
}
