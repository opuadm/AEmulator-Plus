package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRateTest {
    @Test fun atOrBelowTheHostItRendersAtTheChosenRate() {
        for (hz in listOf(30, 45, 50, 60, 75, 90, 120)) {
            val r = RefreshRate.resolve(hz, 120, true)
            assertEquals(RefreshRate.Resolved(hz, hz), r)
            assertFalse(r.capped)
        }
    }

    // above the host: the guest is told the chosen rate, frames come at what the phone can show
    @Test fun aboveTheHostItReportsTheChosenRateAndRendersAtTheHostMaximum() {
        val r = RefreshRate.resolve(144, 120, true)
        assertEquals(144, r.reported)
        assertEquals(120, r.tick)
        assertTrue(r.capped)
        assertEquals(RefreshRate.Resolved(185, 60), RefreshRate.resolve(185, 60, true))
    }

    @Test fun hostMeansThePhonesMaximum() {
        assertEquals(RefreshRate.Resolved(90, 90), RefreshRate.resolve(RefreshRate.HOST, 90, true))
        assertEquals(RefreshRate.Resolved(144, 144), RefreshRate.resolve(RefreshRate.HOST, 144, true))
    }

    // SwiftShader draws on the CPU: whatever is chosen, the guest stays at 60
    @Test fun withoutTheGpuBridgeItStaysAtSixty() {
        assertEquals(RefreshRate.Resolved(60, 60), RefreshRate.resolve(144, 144, false))
        assertEquals(RefreshRate.Resolved(60, 60), RefreshRate.resolve(RefreshRate.HOST, 120, false))
    }

    @Test fun anUnusableStoredValueMeansSixty() {
        for (bad in listOf(0, -2, 5, 5000)) assertEquals(RefreshRate.Resolved(60, 60), RefreshRate.resolve(bad, 120, true))
        assertEquals(60, RefreshRate.legacyHz(RefreshRate.HOST))
        assertEquals(45, RefreshRate.legacyHz(45))
    }

    @Test fun customRatesAreKeptWithinTheRange() {
        assertEquals(RefreshRate.Resolved(10, 10), RefreshRate.resolve(10, 120, true))
        assertEquals(RefreshRate.Resolved(240, 120), RefreshRate.resolve(240, 120, true))
    }
}
