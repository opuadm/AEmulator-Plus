package app.aemu.archive

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Needs the native library: run with -Daemu.nativearc.lib=<libaemuarc.so built by native/archive/build.sh host>, otherwise the
 * tests are skipped (there is no Java RAR reader in RarReaders; the importer uses junrar then). Every entry is CRC32-checked inside
 * the reader, so a clean read means the bytes are right.
 */
class RarReadersTest {
    private fun fixture(name: String): File {
        val url = javaClass.classLoader!!.getResource("rar/$name")
        assumeTrue("fixture $name missing", url != null)
        return File(url!!.toURI())
    }

    private fun readAll(name: String): List<Pair<String, Long>> {
        assumeTrue("libaemuarc not loaded", NativeArc.AVAILABLE)
        val out = ArrayList<Pair<String, Long>>()
        FileChannel.open(fixture(name).toPath(), StandardOpenOption.READ).use { ch ->
            RarReaders.open(ch).use { r ->
                val buf = ByteArray(8192)
                while (true) {
                    val e = r.nextEntry() ?: break
                    var n = 0L
                    while (true) { val k = r.read(buf, 0, buf.size); if (k < 0) break; n += k }
                    if (!e.isDirectory) assertEquals("size of ${e.name}", e.size, n)
                    out.add(e.name to n)
                }
            }
        }
        return out
    }

    @Test fun rar3SolidArchive() {
        val e = readAll("rarfile-rar3-solid.rar")
        assertEquals(2, e.size)
        assertTrue(e.all { it.second == 2048L })
    }

    @Test fun rar5SolidArchive() {
        val e = readAll("rarfile-rar5-solid.rar")
        assertEquals(listOf("stest1.txt", "stest2.txt"), e.map { it.first })
    }

    @Test fun rar3FilteredExecutable() {   // E8E9 + Delta filters
        val e = readAll("libarchive-rar_filter.rar")
        assertEquals(listOf("bsdcat.exe" to 204288L), e)
    }

    @Test fun rar5ArmFilter() {
        val e = readAll("libarchive-rar5_arm.rar")
        assertEquals(90808L, e.single().second)
    }

    @Test fun rar2CompressionIsRefused() {
        assumeTrue("libaemuarc not loaded", NativeArc.AVAILABLE)
        FileChannel.open(fixture("rarfile-rar3-old.rar").toPath(), StandardOpenOption.READ).use { ch ->
            try { RarReaders.open(ch).close(); fail("expected UnsupportedOperationException") }
            catch (e: UnsupportedOperationException) { /* the importer falls back to junrar */ }
        }
    }
}
