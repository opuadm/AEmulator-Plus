/* Added for AEmulator Plus (2026-11-26). GPL-3.0; see LICENSE. */
package app.aemu.core

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class IwmmxtPrelinkTagTest {
    private val tag = byteArrayOf(0x00, 0x00, 0x70, 0x2a, 0x50, 0x52, 0x45, 0x20)      // <load address> + "PRE "

    @Test fun tagIsRecognizedOnlyAtTheEndOfTheFile() {
        assertTrue(Iwmmxt.hasPrelinkTag(ByteArray(64) + tag))
        assertFalse(Iwmmxt.hasPrelinkTag(tag + ByteArray(64)))
        assertFalse(Iwmmxt.hasPrelinkTag(ByteArray(4)))
    }

    @Test fun libraryWrittenWithTheCodeAfterTheTagCountsAsLost() {
        val original = ByteArray(64) + tag
        assertTrue(Iwmmxt.prelinkTagLost(original, original + ByteArray(32)))
        assertFalse(Iwmmxt.prelinkTagLost(original, ByteArray(64) + ByteArray(32) + tag))
        assertFalse(Iwmmxt.prelinkTagLost(ByteArray(64), ByteArray(96)))
    }
}
