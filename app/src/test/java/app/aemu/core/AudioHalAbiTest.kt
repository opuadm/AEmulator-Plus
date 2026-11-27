package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class AudioHalAbiTest {
    private fun fixture(): ByteArray = ByteArray(256).apply {
        byteArrayOf(0x7f, 69, 76, 70, 1, 1).copyInto(this)
        this[18] = 40
        "AudioStreamOutSink".toByteArray().copyInto(this, 64)
        byteArrayOf(0x13, 0xb5.toByte(), 0x0c, 0x46, 0x80.toByte(), 0x69,
            0x83.toByte(), 0x6e, 0x13, 0xb9.toByte(), 0x6f, 0xf0.toByte(), 0x25, 0x00).copyInto(this, 128)
    }
    /** LG-D722 5.0.1: the same +0x68 reader behind a different function prologue (only the reader itself is checked). */
    private fun fixtureOtherPrologue(): ByteArray = fixture().apply {
        byteArrayOf(0x2d, 0xe9.toByte(), 0xf0.toByte(), 0x41, 0x0c, 0x46).copyInto(this, 128)
    }
    @Test fun recognizesVerifiedThumbLayout() {
        assertTrue(AudioHalAbi.usesDirectTrackTail(fixture()))
        assertFalse(AudioHalAbi.usesDirectTrackTail(fixture().apply { this[134] = 0x03 })) // AOSP slot 0x60
        assertFalse(AudioHalAbi.usesDirectTrackTail(fixture().apply { this[64] = 0 }))
    }
    @Test fun recognizesReaderWithAnyPrologue() {
        assertTrue(AudioHalAbi.usesDirectTrackTail(fixtureOtherPrologue()))
        assertFalse(AudioHalAbi.usesDirectTrackTail(fixtureOtherPrologue().apply { this[134] = 0x03 })) // AOSP slot 0x60
        assertFalse(AudioHalAbi.usesDirectTrackTail(fixtureOtherPrologue().apply { this[138] = 0x24 })) // not mvn r0,#37
    }
    @Test fun rejectsWrongArchitectureAndTruncation() {
        assertFalse(AudioHalAbi.usesDirectTrackTail(byteArrayOf()))
        assertFalse(AudioHalAbi.usesDirectTrackTail(fixture().copyOf(135)))
        for (offset in listOf(0, 4, 5, 18, 19)) {
            assertFalse(AudioHalAbi.usesDirectTrackTail(fixture().apply { this[offset] = 2 }))
        }
    }
    @Test fun directTrackApiCoversKitKatThroughLollipop() {
        for (api in 19..22) assertTrue(api.toString(), AudioHalAbi.directTrackApi(api))
        for (api in listOf(0, 16, 18, 23, 29)) assertFalse(api.toString(), AudioHalAbi.directTrackApi(api))
    }
}
