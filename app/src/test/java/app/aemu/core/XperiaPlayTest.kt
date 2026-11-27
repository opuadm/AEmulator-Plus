package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XperiaPlayTest {
    private data class Ev(val type: Int, val code: Int, val value: Int)

    private fun events(a: ByteArray): List<Ev> {
        val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until a.size / 16).map { Ev(b.getShort(it * 16 + 8).toInt(), b.getShort(it * 16 + 10).toInt(), b.getInt(it * 16 + 12)) }
    }

    @Test fun sliderOutIsLidOpen() {
        val ev = events(SliderProtocol().frame(true, 5_000_000_000L))
        assertEquals(listOf(Ev(5, 0, 0), Ev(0, 0, 0)), ev)         // EV_SW SW_LID 0, SYN_REPORT
    }

    @Test fun sliderInIsLidShut() {
        assertEquals(listOf(Ev(5, 0, 1), Ev(0, 0, 0)), events(SliderProtocol().frame(false, 1L)))
    }

    @Test fun sliderFrameCarriesTheTime() {
        val b = ByteBuffer.wrap(SliderProtocol().frame(true, 7_000_250_000L)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7, b.getInt(0)); assertEquals(250, b.getInt(4))
        assertEquals(32, SliderProtocol().frame(true, 0).size)
    }

    @Test fun onlyAnR800FamilyModelIsOffered() {
        assertTrue(VirtualInputPolicy.isXperiaPlayModel("R800i"))
        assertTrue(VirtualInputPolicy.isXperiaPlayModel(" r800I "))
        for (m in listOf("R800a", "R800at", "R800x", "r800AT")) assertTrue(m, VirtualInputPolicy.isXperiaPlayModel(m))
        assertFalse(VirtualInputPolicy.isXperiaPlayModel("R800i1234"))
        assertFalse(VirtualInputPolicy.isXperiaPlayModel("R8000"))
        assertFalse(VirtualInputPolicy.isXperiaPlayModel("R801i"))
        assertFalse(VirtualInputPolicy.isXperiaPlayModel("GT-N7000"))
        assertFalse(VirtualInputPolicy.isXperiaPlayModel(""))
    }

    @Test fun supportedOnQemuEnginesFromGingerbread() {
        val e = Engine.entries.first { it != Engine.N64 }
        assertTrue(VirtualInputPolicy.xperiaPlaySupported(e, "R800i", false, false, false, 10))
        assertTrue(VirtualInputPolicy.xperiaPlaySupported(e, "R800i", false, false, false, 19))
        assertFalse(VirtualInputPolicy.xperiaPlaySupported(e, "R800i", false, false, false, 8))
        assertFalse(VirtualInputPolicy.xperiaPlaySupported(e, "R800i", true, false, false, 10))     // recovery
        assertFalse(VirtualInputPolicy.xperiaPlaySupported(e, "R800i", false, true, false, 10))     // framework-less boot
        assertFalse(VirtualInputPolicy.xperiaPlaySupported(e, "R800i", false, false, true, 10))     // pre-1.0
        assertFalse(VirtualInputPolicy.xperiaPlaySupported(e, "SM-N900", false, false, false, 10))
        assertFalse(VirtualInputPolicy.xperiaPlaySupported(Engine.N64, "R800i", false, false, false, 10))
    }

    @Test fun padKeysAreDistinctAndUseTheDocumentedCodes() {
        assertEquals(XperiaPlayPad.all.size, XperiaPlayPad.all.map { it.code }.toSet().size)
        assertEquals(232, XperiaPlayPad.cross.code)                    // DPAD_CENTER
        assertEquals(InputService.KEY_BACK, XperiaPlayPad.circle.code)
        assertEquals(VirtualKeys.UP, XperiaPlayPad.up.code)
    }

    @Test fun keyLayoutLabelsEveryScanCodeThePadNeedsThatTheStockLayoutLacks() {
        val lines = XperiaPlayPad.keyLayout().lines().filter { it.startsWith("key ") }.associate { l -> l.split(" ").let { it[1].toInt() to it[2] } }
        val stock = setOf(VirtualKeys.UP, VirtualKeys.DOWN, VirtualKeys.LEFT, VirtualKeys.RIGHT, VirtualKeys.SELECT, InputService.KEY_BACK, InputService.KEY_MENU)
        for (k in XperiaPlayPad.all) assertTrue("${k.label} (${k.code})", k.code in stock || k.code in lines)
        assertEquals("BUTTON_X", lines[XperiaPlayPad.square.code]); assertEquals("BUTTON_Y", lines[XperiaPlayPad.triangle.code])
        assertEquals("BUTTON_L1", lines[XperiaPlayPad.l.code]); assertEquals("BUTTON_R1", lines[XperiaPlayPad.r.code])
        assertEquals("BUTTON_MODE", lines[XperiaPlayPad.play.code])
    }
}
