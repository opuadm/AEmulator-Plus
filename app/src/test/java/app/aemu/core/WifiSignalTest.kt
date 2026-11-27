/* AEmulator Super Plus, 2026-10-11: Wi-Fi signal level. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Properties

class WifiSignalTest {
    private fun props(s: VmSettings) = Properties().apply { load(ByteArrayInputStream(WifiEmu.encode(WifiEmu.properties(s)))) }

    @Test fun signalBarsMapToRssi() {
        assertEquals("-48", props(VmSettings()).getProperty("rssi"))     // the old fixed value stays the default
        assertEquals("-84", props(VmSettings(wifiSignal = 1)).getProperty("rssi"))
        // Android's calculateSignalLevel(rssi, 5): (rssi + 100) * 4 / 45
        for (bars in 1..4) assertEquals(bars, (WifiEmu.rssiFor(bars) + 100) * 4 / 45)
    }
}
