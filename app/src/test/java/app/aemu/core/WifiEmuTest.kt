package app.aemu.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.util.Properties

class WifiEmuTest {
    @Test fun ssidIsCutAtThirtyTwoBytesOnACharacterBoundary() {
        assertEquals("ж".repeat(16), WifiEmu.cleanSsid("ж".repeat(40)))
        assertEquals(8, WifiEmu.cleanSsid("😀".repeat(10)).codePointCount(0, WifiEmu.cleanSsid("😀".repeat(10)).length))
        assertEquals("Café", WifiEmu.cleanSsid("Café\u0000\n"))
    }
    @Test fun passwordRules() {
        assertEquals("pssword1", WifiEmu.cleanPassword("pässword1"))
        assertEquals(63, WifiEmu.cleanPassword("a".repeat(80)).length)
        assertTrue(WifiEmu.passwordValid(""))
        assertFalse(WifiEmu.passwordValid("short"))
        assertTrue(WifiEmu.passwordValid("12345678"))
        assertFalse(WifiEmu.secured(VmSettings(wifiSecurity = WifiSecurity.WPA2, wifiPassword = "short")))
        assertTrue(WifiEmu.secured(VmSettings(wifiSecurity = WifiSecurity.WPA2, wifiPassword = "12345678")))
        assertTrue(WifiEmu.secured(VmSettings(wifiSecurity = WifiSecurity.WPA3, wifiPassword = "12345678")))
        assertFalse(WifiEmu.secured(VmSettings(wifiSecurity = WifiSecurity.NONE, wifiPassword = "12345678")))
        assertTrue(WifiEmu.credentialValid(WifiSecurity.NONE, ""))
        assertFalse(WifiEmu.credentialValid(WifiSecurity.WPA2, ""))
    }
    @Test fun speedParsing() {
        assertEquals(0, WifiEmu.speedKbps(""))
        assertEquals(50_000, WifiEmu.speedKbps("50"))
        assertEquals(50_000, WifiEmu.speedKbps("50 Mbps"))
        assertEquals(50_000, WifiEmu.speedKbps("50mb/s"))
        assertEquals(800, WifiEmu.speedKbps("800 Kbps"))
        assertEquals(1_000_000, WifiEmu.speedKbps("1 Gbps"))
        assertEquals(1_500, WifiEmu.speedKbps("1,5 mbit/s"))
        assertEquals(null, WifiEmu.speedKbps("fast"))
        assertEquals(null, WifiEmu.speedKbps("0 Mbps"))
        assertEquals(null, WifiEmu.speedKbps("3000 Gbps"))
        assertEquals(1_000_000_000, WifiEmu.speedKbps("1000 Gbps"))
        assertEquals(0, WifiEmu.speedKbps("Gbps"))
        assertEquals("" to "Gbps", WifiEmu.speedParts("Gbps"))
        assertEquals("1,5" to "Mbps", WifiEmu.speedParts("1,5 mbit/s"))
        assertEquals("800" to "Kbps", WifiEmu.speedParts("800 Kbps"))
        assertEquals("50 Gbps", WifiEmu.speedJoin("50", "Gbps"))
    }
    @Test fun bssidIsStableAndLocallyAdministered() {
        assertEquals(WifiEmu.bssid("home"), WifiEmu.bssid("home"))
        assertNotEquals(WifiEmu.bssid("home"), WifiEmu.bssid("work"))
        assertTrue(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(WifiEmu.bssid("home")))
        assertEquals(2, WifiEmu.bssid("home").substring(0, 2).toInt(16) and 3)
    }
    @Test fun supportedGuests() {
        assertTrue(WifiEmu.supported(Engine.KK, 14, false, false))
        assertTrue(WifiEmu.supported(Engine.KK, 19, false, false))
        assertTrue(WifiEmu.supported(Engine.KK, 25, false, false))
        assertTrue(WifiEmu.supported(Engine.KK, 9, false, false))   // Gingerbread 2.3
        assertTrue(WifiEmu.supported(Engine.KK, 10, false, false))  // Gingerbread 2.3.3-2.3.7
        assertFalse(WifiEmu.supported(Engine.KK, 8, false, false))  // Froyo
        for (api in 11..13) assertFalse(WifiEmu.supported(Engine.KK, api, false, false)) // Honeycomb: not looked at
        assertFalse(WifiEmu.supported(Engine.GB, 10, false, false))
        assertFalse(WifiEmu.supported(Engine.KK, 10, true, false))
        assertFalse(WifiEmu.supported(Engine.KK, 26, false, false))
        assertFalse(WifiEmu.supported(Engine.GB, 19, false, false))
        assertFalse(WifiEmu.supported(Engine.KK, 19, true, false))
        assertFalse(WifiEmu.supported(Engine.KK, 19, false, true))
    }
    @Test fun androidEightToSeventeenRunOnTheSixtyFourBitEngine() {
        for (api in 26..37) assertTrue("api $api", WifiEmu.supported(Engine.N64, api, false, false))
        for (api in 14..25) assertTrue("api $api", WifiEmu.supported(Engine.N64, api, false, false))
        assertFalse(WifiEmu.supported(Engine.N64, 13, false, false))
        assertFalse(WifiEmu.supported(Engine.N64, 38, false, false))
        assertFalse(WifiEmu.supported(Engine.N64, 30, true, false))    // recovery
        assertFalse(WifiEmu.supported(Engine.N64, 30, false, true))    // native UI / low power boot
        assertFalse(WifiEmu.supported(Engine.KK, 26, false, false))
        assertFalse(WifiEmu.supported(Engine.GB, 30, false, false))
    }
    /** An image as the importer stores it: always the universal engine, whatever the firmware needs (Analyzer: Engine.forApi). */
    private fun stored(api: Int, abi: String = "armeabi-v7a") = GuestImage(
        id = "t", name = "t", release = "", api = api, brand = "", model = "", skin = "", engine = Engine.KK, abi = abi,
        bootclasspath = "", exports = emptyMap(), dirs = emptyList(), services = emptyList(), sdcardPath = "",
        settings = VmSettings(), createdAt = 0,
    )
    @Test fun settingsOfferWifiForAndroidEightToSeventeenWhateverEngineIsStored() {
        for (api in 26..37) {
            assertTrue("arm64 api $api", WifiEmu.offered(stored(api, "arm64-v8a")))
            assertTrue("32-bit api $api", WifiEmu.offered(stored(api)))
        }
        assertFalse(WifiEmu.offered(stored(38, "arm64-v8a")))
        // 32-bit translation off: the firmware stays on the universal engine, which has no service for 8.0+
        assertFalse(WifiEmu.offered(stored(30).let { it.copy(settings = it.settings.copy(translate32 = false)) }))
        // older firmware keeps the universal engine and its range
        for (api in listOf(9, 10, 14, 19, 25)) assertTrue("api $api", WifiEmu.offered(stored(api)))
        for (api in listOf(8, 11, 12, 13)) assertFalse("api $api", WifiEmu.offered(stored(api)))
    }
    @Test fun propertiesRoundTrip() {
        val s = VmSettings(wifi = true, wifiSsid = "Мой \"Wi-Fi\" = test", wifiPassword = "pa ss:w0rd#!")
        val p = Properties().apply { load(String(WifiEmu.encode(WifiEmu.properties(s)), Charsets.ISO_8859_1).let(::StringReader)) }
        assertEquals(s.wifiSsid, p.getProperty("ssid"))
        assertEquals("true", p.getProperty("secured"))
        assertEquals(s.wifiPassword, p.getProperty("psk"))
        val open = WifiEmu.properties(VmSettings(wifi = true, wifiSsid = "Open", wifiPassword = ""))
        assertEquals("false", open.getProperty("secured"))
        assertEquals("", open.getProperty("psk"))
    }
    @Test fun fileIsWrittenAndRemoved() {
        val root = File.createTempFile("wifi", "").apply { delete(); mkdirs() }
        try {
            WifiEmu.write(root, VmSettings(wifi = true), true)
            assertTrue(File(root, WifiEmu.FILE).isFile)
            WifiEmu.write(root, VmSettings(wifi = true), false)
            assertFalse(File(root, WifiEmu.FILE).exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun settingsRoundTripThroughJson() {
        val s = VmSettings(wifi = true, wifiSsid = "Home", wifiPassword = "hunter2hunter2")
        val back = VmSettings.fromJson(s.toJson())
        assertTrue(back.wifi); assertEquals("Home", back.wifiSsid); assertEquals("hunter2hunter2", back.wifiPassword)
        assertFalse(VmSettings.fromJson(org.json.JSONObject()).wifi)
        assertEquals(WifiEmu.DEFAULT_SSID, VmSettings.fromJson(org.json.JSONObject().put("wifiSsid", "")).wifiSsid)
    }
    @Test fun bandAndStandardReachTheGuestAndSurviveJson() {
        val s = VmSettings(wifi = true, wifiBand = WifiBand.DUAL, wifiStandard = WifiStandard.BE,
            wifiExtra = listOf(WifiNetworkDef("Other", "password123", band = WifiBand.GHZ6, standard = WifiStandard.AC)))
        val p = WifiEmu.properties(s)
        assertEquals("dual", p.getProperty("band")); assertEquals("be", p.getProperty("standard"))
        assertTrue(p.getProperty("extras").endsWith("|6|ac"))
        val back = VmSettings.fromJson(s.toJson())
        assertEquals(WifiBand.DUAL, back.wifiBand); assertEquals(WifiStandard.BE, back.wifiStandard)
        assertEquals(WifiBand.GHZ6, back.wifiExtra[0].band); assertEquals(WifiStandard.AC, back.wifiExtra[0].standard)
        assertEquals(WifiBand.GHZ24, VmSettings.fromJson(org.json.JSONObject()).wifiBand)
        assertEquals(WifiStandard.AUTO, WifiStandard.fromId("nonsense"))
    }
}
