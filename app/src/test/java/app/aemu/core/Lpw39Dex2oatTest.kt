package app.aemu.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Lpw39Dex2oatTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): File = tmp.newFolder("root").also { File(it, "system/bin").mkdirs() }

    @Test fun scriptHasNoCarriageReturnsAndAShebang() {
        val s = Lpw39Dex2oat.script()
        assertTrue(s.startsWith("#!/system/bin/sh\n"))
        assertFalse(s.contains('\r'))
        assertTrue(s.contains("dex2oat.real"))
    }
    @Test fun movesTheOriginalAndWritesTheWrapper() {
        val root = tree()
        File(root, "system/bin/dex2oat").writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        assertTrue(Lpw39Dex2oat.install(root))
        assertTrue(Lpw39Dex2oat.isWrapper(File(root, "system/bin/dex2oat")))
        assertEquals(4L, File(root, "system/bin/dex2oat.real").length())
    }
    @Test fun secondRunChangesNothing() {
        val root = tree()
        File(root, "system/bin/dex2oat").writeText("firmware")
        assertTrue(Lpw39Dex2oat.install(root))
        assertFalse(Lpw39Dex2oat.install(root))
        assertEquals("firmware", File(root, "system/bin/dex2oat.real").readText())
    }
    @Test fun reimportedFirmwareReplacesTheOldReal() {
        val root = tree()
        File(root, "system/bin/dex2oat").writeText("v1")
        Lpw39Dex2oat.install(root)
        File(root, "system/bin/dex2oat").delete()
        File(root, "system/bin/dex2oat").writeText("v2")
        assertTrue(Lpw39Dex2oat.install(root))
        assertEquals("v2", File(root, "system/bin/dex2oat.real").readText())
    }
    @Test fun noDex2oatMeansNothingToDo() {
        assertFalse(Lpw39Dex2oat.install(tree()))
    }
    @Test fun imageRunPinsClassesZipAndSerialisesBuilds() {
        val s = Lpw39Dex2oat.script()
        assertTrue(s.contains("--image-classes-zip=/system/framework/aemu-imgclasses.zip"))
        assertTrue(s.contains("mkdir \"\$lock\""))
        assertTrue(s.contains("--compiler-filter=interpret-only"))
    }
    @Test fun wrapperNeverRunsHelpToProbeTheFilter() {
        // dex2oat prints its usage to logcat, not to a pipe: a "--help | grep" inside the wrapper can never match
        assertFalse(Lpw39Dex2oat.script().contains("--help"))
        assertFalse(Lpw39Dex2oat.script(false).contains("--help"))
    }
    @Test fun filterIsAddedOnlyWhenTheBinaryOffersIt() {
        val on = tree()
        File(on, "system/bin/dex2oat").writeBytes("\u0000\u0001--compiler-filter=(interpret-only|space)\u0000".toByteArray(Charsets.ISO_8859_1))
        assertTrue(Lpw39Dex2oat.install(on))
        assertTrue(File(on, "system/bin/dex2oat").readText(Charsets.ISO_8859_1).contains("addcf=1"))
        assertFalse(Lpw39Dex2oat.install(on))                       // stable on the next boot

        val off = tree()
        File(off, "system/bin/dex2oat").writeText("an older dex2oat without the option")
        assertTrue(Lpw39Dex2oat.install(off))
        val text = File(off, "system/bin/dex2oat").readText(Charsets.ISO_8859_1)
        assertFalse(text.contains("addcf=1"))
        assertTrue(text.contains("addcf=\"") || text.contains("addcf=\n") || text.contains("&& addcf=\n"))
    }
    @Test fun anOldWrapperWithTheHelpProbeIsReplaced() {
        val root = tree()
        File(root, "system/bin/dex2oat.real").writeText("--compiler-filter=")
        File(root, "system/bin/dex2oat").writeText(Lpw39Dex2oat.MARKER + "\n/system/bin/dex2oat.real --help 2>&1 | grep -q -e --compiler-filter\n")
        assertTrue(Lpw39Dex2oat.install(root))
        assertFalse(File(root, "system/bin/dex2oat").readText(Charsets.ISO_8859_1).contains("--help"))
    }
    @Test fun offersCompilerFilterSeesTheNameAcrossAReadBoundary() {
        val f = tmp.newFile("bin")
        val needle = "--compiler-filter=".toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(200_000) { 'a'.code.toByte() }
        needle.copyInto(data, (1 shl 16) - 5)                       // straddles the first 64 KiB chunk
        f.writeBytes(data)
        assertTrue(Lpw39Dex2oat.offersCompilerFilter(f))
        f.writeBytes(ByteArray(200_000) { 'a'.code.toByte() })
        assertFalse(Lpw39Dex2oat.offersCompilerFilter(f))
        assertFalse(Lpw39Dex2oat.offersCompilerFilter(File(tmp.root, "missing")))
    }
}
