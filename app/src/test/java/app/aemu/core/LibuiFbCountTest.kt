package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

/** GrallocProbe.libuiTakesFbCount: HTC's Gingerbread libui logs the FIFO length it read from fb0 ("mNumBuffers = %d"); AOSP's does not. */
class LibuiFbCountTest {
    private fun elfLike(vararg strings: String): ByteArray =
        (listOf("libui.so", "FramebufferNativeWindow") + strings).joinToString("\u0000").toByteArray(Charsets.ISO_8859_1)

    @Test fun htcLibuiThatLogsItsBufferCountTakesItFromFb0() {
        assertTrue(GrallocProbe.libuiTakesFbCount(elfLike("mNumBuffers = %d", "couldn't open framebuffer HAL (%s)")))
    }

    @Test fun aospLibuiWithAConstantFifoDoesNot() {
        assertFalse(GrallocProbe.libuiTakesFbCount(elfLike("couldn't open framebuffer HAL (%s)", "couldn't open gralloc HAL (%s)")))
    }

    @Test fun anEmptyFileDoesNot() {
        assertFalse(GrallocProbe.libuiTakesFbCount(ByteArray(0)))
    }
}
