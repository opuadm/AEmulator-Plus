package app.aemu.core

import app.aemu.core.GrallocProbe.SinglePage
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The single-page fb0 deadlock probe (GrallocProbe.singlePageDeadlock): a firmware gralloc is replaced when it has the pmem path, the
 * module mutex and the single-page branch together, not because of its API level.
 *
 * The positive fixture is engines/kk-x86 (the AOSP Eclair gralloc the engine ships for x86): it carries the structure of the buggy module
 * (its single-page branch is fixed, which no static check of strings and imports can tell). That is why TreeFixer asks about the firmware's
 * own copy only, never about the module in place.
 */
class SinglePageGrallocTest {
    private val engines = File("src/main/assets/engines")
    private fun bytes(path: String) = File(engines, path).readBytes()

    /** [s] replaced by [with] (same length) at its first occurrence */
    private fun swap(d: ByteArray, s: String, with: String): ByteArray {
        require(s.length == with.length)
        val a = s.toByteArray(Charsets.ISO_8859_1); val b = with.toByteArray(Charsets.ISO_8859_1)
        val at = (0..d.size - a.size).first { i -> a.indices.all { d[i + it] == a[it] } }
        return d.copyOf().also { b.copyInto(it, at) }
    }

    @Test fun pmemModuleUnderTheMutexDeadlocks() {
        assertEquals(SinglePage.DEADLOCKS, GrallocProbe.singlePageDeadlock(bytes("kk-x86/gralloc.default.so")))
    }

    @Test fun ashmemOnlyGrallocsAreSafe() {
        assertEquals(SinglePage.SAFE, GrallocProbe.singlePageDeadlock(bytes("gb/gralloc.default.so")))
        assertEquals(SinglePage.SAFE, GrallocProbe.singlePageDeadlock(bytes("kk/gralloc.default.so")))
        assertEquals(SinglePage.SAFE, GrallocProbe.singlePageDeadlock(bytes("kk/${GtiGralloc.ASSET}")))
    }

    @Test fun eachOfTheThreeFactsIsNeeded() {
        val bad = bytes("kk-x86/gralloc.default.so")
        assertEquals(SinglePage.SAFE, GrallocProbe.singlePageDeadlock(swap(bad, "/dev/pmem", "/dev/pmeX")))
        assertEquals(SinglePage.SAFE, GrallocProbe.singlePageDeadlock(swap(bad, "page flipping not supported", "page flippinX not supported")))
        assertEquals(SinglePage.SAFE, GrallocProbe.singlePageDeadlock(swap(bad, "pthread_mutex_lock", "pthread_mutex_loXk")))
    }

    @Test fun whatIsNotAGrallocModuleIsUnknown() {
        val bad = bytes("kk-x86/gralloc.default.so")
        assertEquals(SinglePage.UNKNOWN, GrallocProbe.singlePageDeadlock(ByteArray(0)))
        assertEquals(SinglePage.UNKNOWN, GrallocProbe.singlePageDeadlock("not an elf, just text /dev/pmem".toByteArray()))
        assertEquals(SinglePage.UNKNOWN, GrallocProbe.singlePageDeadlock(bad.copyOf(0x40)))                  // truncated headers
        assertEquals(SinglePage.UNKNOWN, GrallocProbe.singlePageDeadlock(bad.copyOf().also { it[18] = 62 }))  // x86-64
        assertEquals(SinglePage.UNKNOWN, GrallocProbe.singlePageDeadlock(bad.copyOf().also { it[4] = 2 }))    // ELF64
        // no HMI symbol: a library with a pmem path that is not the HAL module
        assertEquals(SinglePage.UNKNOWN, GrallocProbe.singlePageDeadlock(swap(bad, "\u0000HMI\u0000", "\u0000HMX\u0000")))
    }
}
