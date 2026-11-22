package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RamTypeTest {
    @Test fun parseKnownAndUnknown() {
        assertEquals(RamType.LPDDR5X, RamType.parse("lpddr5x"))
        assertEquals(RamType.LPDDR2, RamType.parse("lpddr2"))
        assertNull(RamType.parse(""))
        assertNull(RamType.parse("ddr9"))
    }

    @Test fun settingsRoundTripAndOldFilesGetDefault() {
        val s = VmSettings(ramType = RamType.LPDDR4X.id)
        assertEquals("lpddr4x", VmSettings.fromJson(s.toJson()).ramType)
        val old = s.toJson().apply { remove("ramType") }
        assertEquals("", VmSettings.fromJson(old).ramType)
    }

    @Test fun propsOnlyWhenChosen() {
        assertTrue(RamSpoof.props(null).isEmpty())
        assertEquals("LPDDR5", RamSpoof.props(RamType.LPDDR5)["ro.boot.ram_type"])
    }
}
