package app.aemu.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibcPropLayoutTest {
    private fun libc(): ByteArray = javaClass.getResourceAsStream("/libc-androidx86-51.so")!!.readBytes()

    @Test fun androidX86LibcReadsTheSerialAt0x80() {
        assertEquals(0x80, LibcPropLayout.serialOffset(libc()))
    }

    @Test fun notElf32X86IsUnknown() {
        assertNull(LibcPropLayout.serialOffset(ByteArray(0)))
        assertNull(LibcPropLayout.serialOffset(ByteArray(4096)))
        val arm = libc().also { it[18] = 40 }
        assertNull(LibcPropLayout.serialOffset(arm))
    }

    @Test fun treeWithoutLibcKeepsTheClassicLayout() {
        val root = Files.createTempDirectory("libcprop").toFile()
        assertEquals(LibcPropLayout.CLASSIC_NAME_MAX, LibcPropLayout.nameMaxFor(root))
        File(root, "system/lib").mkdirs()
        File(root, "system/lib/libc.so").writeBytes(libc())
        assertEquals(0x80, LibcPropLayout.nameMaxFor(root))
    }

    /** what __system_property_find_compat + read_compat do: TOC at 0x20, serial at [serial], value after it */
    private fun compatGet(f: File, serial: Int, name: String): String? {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until b.getInt(0)) {
            val e = b.getInt(0x20 + i * 4)
            if ((e ushr 24) != name.length) continue
            val off = e and 0xffffff
            if (String(ByteArray(name.length) { b.get(off + it) }) != name) continue
            val len = b.getInt(off + serial) ushr 24
            assertTrue("length byte $len would overflow property_get's 92-byte buffer", len < 92)
            return String(ByteArray(len) { b.get(off + serial + 4 + it) })
        }
        return null
    }

    @Test fun areaFollowsTheDetectedLayout() {
        val f = File.createTempFile("props", ".area")
        PropArea.create(f, 22, 0x80).use { a ->
            for (i in 0 until 130) assertTrue(a.put("ro.test.key$i", "value$i"))
            assertTrue(a.put("ro.kernel.qemu", "1"))
        }
        assertEquals("1", compatGet(f, 0x80, "ro.kernel.qemu"))
        assertEquals("value77", compatGet(f, 0x80, "ro.test.key77"))
        PropArea.open(f)!!.use { a ->
            assertEquals("1", a.get("ro.kernel.qemu"))
            assertEquals(131, a.count)
            assertTrue(a.put("ro.kernel.qemu", "0"))
            assertEquals("0", a.get("ro.kernel.qemu"))
        }
        assertEquals("0", compatGet(f, 0x80, "ro.kernel.qemu"))
    }

    @Test fun classicAreaIsUnchanged() {
        val f = File.createTempFile("props", ".area")
        PropArea.create(f, 22).use { a -> assertTrue(a.put("ro.kernel.qemu", "1")) }
        assertEquals("1", compatGet(f, 32, "ro.kernel.qemu"))
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, b.getInt(PropArea.V1_LAYOUT_MARK_AT))
        assertNotNull(PropArea.open(f))
    }
}
