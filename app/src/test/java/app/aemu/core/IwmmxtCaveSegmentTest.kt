package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IwmmxtCaveSegmentTest {
    @Test fun caveSegmentIsWritableSoTheLinkerKeepsItOutOfItsWriteProtectSpan() {
        assertTrue(Iwmmxt.CAVE_FLAGS and 2 != 0)          // PF_W
        assertEquals(7, Iwmmxt.CAVE_FLAGS)                // R+W+X
    }
}
