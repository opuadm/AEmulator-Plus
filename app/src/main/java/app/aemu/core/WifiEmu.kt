/* Modified for AEmulator Super Plus, 2026-10-11: signal level and neighbouring networks.
 * Modified for AEmulator Plus, 2026-11-01: Android 8 - 17 on the 64-bit engine.
 * Modified for AEmulator Plus, 2026-11-02: the settings screen asks about the engine the VM really runs on. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Emulated Wi-Fi. The guest has no wireless hardware and does not need any: its sockets already go out through the
 * host's network. What this adds is the Wi-Fi *identity* of that connection. The guest-side service (guest-wifi,
 * aemu-wifi.jar) answers WifiManager as a connected access point called [VmSettings.wifiSsid] (WPA2 when a password is
 * set, open otherwise) and registers the connection with ConnectivityService as a Wi-Fi network instead of Ethernet.
 *
 * The host hands its settings over in [FILE], a java.util.Properties file in the guest's /data/system.
 */
object WifiEmu {
    const val DEFAULT_SSID = "AEmulator-WiFi"
    const val SSID_MAX_BYTES = 32
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 63

    /** Settings file inside the guest root. */
    const val FILE = "data/system/aemu-wifi.properties"
    const val JAR_NAME = "aemu-wifi.jar"
    const val JAR_PATH = "system/framework/$JAR_NAME"

    /** Same addressing as the Ethernet placeholder network of the 5.0–7.x stub (qemu "user" network layout). */
    const val IP = "10.0.2.15"
    const val GATEWAY = "10.0.2.2"
    const val DNS1 = "8.8.8.8"
    const val DNS2 = "1.1.1.1"
    const val PREFIX = 24
    const val MAC = "02:15:5d:ae:00:01"

    /** Oldest guest of the main range: Android 4.0 (API 14); Nougat (25) is the newest the engine runs. */
    const val MIN_API = 14
    const val MAX_API = 25

    /**
     * Gingerbread (2.3 = API 9, 2.3.3-2.3.7 = API 10) is supported as a separate range. There the framework's WifiStateTracker
     * belongs to ConnectivityService and takes no broadcasts, so the guest sees the connection through WifiManager, Settings
     * and the status bar only (see guest-wifi LegacyWifi); ConnectivityManager keeps reporting the radio network.
     */
    const val GINGERBREAD_MIN_API = 9
    const val GINGERBREAD_MAX_API = 10

    /**
     * Android 8.0 - 17 (API 26 - 37), which run on the 64-bit engine ([Engine.N64]). The same service answers WifiManager there
     * (the guest's own IWifiManager is read at start-up, see guest-wifi WifiService) and registers the connection as a Wi-Fi
     * network with ConnectivityService; when the guest refuses it as a Wi-Fi network it registers the Ethernet placeholder
     * instead, so the guest keeps its internet either way.
     */
    const val TREBLE_MIN_API = 26
    const val TREBLE_MAX_API = 37

    /** Firmware of 4.0 - 7.1 that runs on the 64-bit engine (arm64 or x86-64 builds) gets the same service. */
    const val N64_MIN_API = 14

    /** Guests with the Wi-Fi service (the engines that have the guest services). */
    fun supported(engine: Engine, api: Int, recovery: Boolean, nativeUi: Boolean) =
        ((engine == Engine.KK && (api in MIN_API..MAX_API || api in GINGERBREAD_MIN_API..GINGERBREAD_MAX_API)) ||
            (engine == Engine.N64 && api in N64_MIN_API..TREBLE_MAX_API)) && !recovery && !nativeUi

    /**
     * Whether the settings screen offers Wi-Fi for [img]. The engine stored with an image is only the importer's default (the
     * universal one, see Analyzer); the VM runs a firmware of Android 8.0+ (and any arm64 one) on the 64-bit engine, picked by
     * [GuestImage.effective] when it starts, and that is the engine [supported] has to be asked about, as GuestVm does.
     * [binder64] only matters below Android 8 and is left off here: finding it out scans the firmware's programs, too slow
     * for a screen that is being drawn.
     */
    fun offered(img: GuestImage, binder64: Boolean = false) =
        supported(img.effective(binder64).engine, img.api, recovery = false, nativeUi = false)

    /** SSID: any printable text, at most 32 bytes in UTF-8 (the 802.11 limit); never cut in the middle of a character. */
    fun cleanSsid(v: String): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < v.length) {
            val cp = v.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x20 || cp == 0x7f || Character.isISOControl(cp)) continue
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > SSID_MAX_BYTES) break
            sb.appendCodePoint(cp); bytes += n
        }
        return sb.toString()
    }

    /** Passphrase: printable ASCII only (WPA2 passphrase alphabet), at most 63 characters. */
    fun cleanPassword(v: String): String = v.filter { it in ' '..'~' }.take(PASSWORD_MAX)

    /** Blank means an open network; otherwise WPA2 needs 8..63 characters. */
    fun passwordValid(v: String) = v.isEmpty() || v.length in PASSWORD_MIN..PASSWORD_MAX
    fun secured(s: VmSettings) = s.wifiSecurity != WifiSecurity.NONE && s.wifiPassword.length in PASSWORD_MIN..PASSWORD_MAX

    /** An open network needs no passphrase; every other type needs 8..63 characters. */
    fun credentialValid(sec: WifiSecurity, v: String) = sec == WifiSecurity.NONE || v.length in PASSWORD_MIN..PASSWORD_MAX
    fun mainValid(s: VmSettings) = credentialValid(s.wifiSecurity, s.wifiPassword) && speedValid(s.wifiSpeed)

    /** Up to 2000 Gbps: the largest value that still fits the kbit/s the guest's link bandwidth is kept in. */
    const val SPEED_MAX_KBPS = 2_000_000_000L

    val SPEED_UNITS = listOf("Kbps", "Mbps", "Gbps")

    fun cleanSpeed(v: String) = v.filter { it in ' '..'~' }.take(24)

    /** kbit/s per unit as typed (Kbps, k, Mb/s, Mbit/s, G, ...; bits, not bytes); a missing unit is Mbps; null = not a unit. */
    private fun unitMul(raw: String): Double? {
        val l = raw.lowercase()
        val unit = l.removeSuffix("/s").removeSuffix("ps").removeSuffix("its").removeSuffix("it").removeSuffix("b")
        if (l.isNotEmpty() && unit.isEmpty()) return null
        return when (unit) { "k" -> 1.0; "", "m" -> 1000.0; "g" -> 1_000_000.0; else -> null }
    }

    /**
     * A speed in kbit/s from text such as "50 Mbps", "800 Kbps", "1,5 Gbps" or "50" (Mbps). Blank, or only a unit (the settings
     * screen keeps the chosen unit while the number is empty) = 0, no limit; null = not understood, zero or above [SPEED_MAX_KBPS].
     */
    fun speedKbps(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        val m = Regex("""^(\d+(?:[.,]\d*)?)?\s*([a-zA-Z/]*)$""").matchEntire(t) ?: return null
        val mul = unitMul(m.groupValues[2]) ?: return null
        val num = m.groupValues[1]
        if (num.isEmpty()) return if (m.groupValues[2].isEmpty()) null else 0
        val kbps = Math.round((num.replace(',', '.').toDoubleOrNull() ?: return null) * mul)
        return if (kbps in 1..SPEED_MAX_KBPS) kbps.toInt() else null
    }

    /** The number and the unit ("Kbps", "Mbps" or "Gbps") of a stored speed text, for the two parts of the settings field. */
    fun speedParts(text: String): Pair<String, String> {
        val m = Regex("""^\s*([0-9]*[.,]?[0-9]*)\s*([a-zA-Z/]*)\s*$""").matchEntire(text) ?: return "" to "Mbps"
        val mul = unitMul(m.groupValues[2]) ?: 1000.0
        return m.groupValues[1] to (if (mul == 1.0) "Kbps" else if (mul == 1_000_000.0) "Gbps" else "Mbps")
    }

    fun speedJoin(number: String, unit: String) = if (number.isEmpty()) unit else "$number $unit"

    /** [kbps] as short text: "800 Kbps", "50 Mbps", "1.5 Gbps". */
    fun speedText(kbps: Int): String = when {
        kbps >= 1_000_000 -> "${(kbps / 1000.0 / 1000.0).toBigDecimal().stripTrailingZeros().toPlainString()} Gbps"
        kbps >= 1000 -> "${(kbps / 1000.0).toBigDecimal().stripTrailingZeros().toPlainString()} Mbps"
        else -> "$kbps Kbps"
    }

    fun speedValid(text: String) = speedKbps(text) != null

    /** Fixed, locally administered BSSID derived from the network name, so it stays the same between boots. */
    fun bssid(ssid: String): String {
        val h = MessageDigest.getInstance("SHA-1").digest(ssid.toByteArray(Charsets.UTF_8))
        val b = IntArray(6) { h[it].toInt() and 0xff }
        b[0] = (b[0] and 0xfc) or 0x02 // unicast, locally administered
        return b.joinToString(":") { "%02x".format(it) }
    }

    /** What the guest service reads. An invalid passphrase (1..7 characters) is not silently turned into an open network. */
    fun properties(s: VmSettings): Properties {
        val ssid = cleanSsid(s.wifiSsid).ifEmpty { DEFAULT_SSID }
        return Properties().apply {
            setProperty("ssid", ssid)
            setProperty("secured", secured(s).toString())
            setProperty("security", if (secured(s)) s.wifiSecurity.id else WifiSecurity.NONE.id)
            setProperty("psk", if (secured(s)) s.wifiPassword else "")
            setProperty("bssid", bssid(ssid))
            setProperty("autoconnect", s.wifiAutoConnect.toString())
            setProperty("speedkbps", (speedKbps(s.wifiSpeed) ?: 0).toString())
            setProperty("mac", MAC)
            setProperty("ip", IP)
            setProperty("gateway", GATEWAY)
            setProperty("prefix", PREFIX.toString())
            setProperty("dns1", DNS1)
            setProperty("dns2", DNS2)
            setProperty("rssi", rssiFor(s.wifiSignal).toString())
            setProperty("speed", "72")
            setProperty("frequency", "2437")
            val extras = usableExtras(s)
            if (extras.isNotEmpty()) setProperty("extras", extras.joinToString("\n") {
                val n = cleanSsid(it.ssid)
                val sec = if (it.security == WifiSecurity.NONE) "none" else it.security.id
                "${hex(n)}|${if (it.security == WifiSecurity.NONE) "" else hex(it.password)}|${rssiFor(it.signal)}|${bssid(n)}|$sec|${speedKbps(it.speed) ?: 0}"
            })
        }
    }

    const val MAX_BARS = 4
    const val MAX_EXTRA = 31

    private fun hex(v: String) = v.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    /** The extra networks that can actually be offered: named, a valid passphrase, no repeated name. */
    fun usableExtras(s: VmSettings): List<WifiNetworkDef> {
        val seen = HashSet<String>().apply { add(cleanSsid(s.wifiSsid).ifEmpty { DEFAULT_SSID }) }
        return s.wifiExtra.take(MAX_EXTRA).filter { cleanSsid(it.ssid).isNotEmpty() && credentialValid(it.security, it.password) && speedValid(it.speed) && seen.add(cleanSsid(it.ssid)) }
    }

    /** Whether every extra network on the screen is complete (a name, no short passphrase, no duplicate name). */
    fun extrasValid(s: VmSettings): Boolean {
        val names = HashSet<String>().apply { add(cleanSsid(s.wifiSsid)) }
        return s.wifiExtra.all { cleanSsid(it.ssid).isNotEmpty() && credentialValid(it.security, it.password) && speedValid(it.speed) && names.add(cleanSsid(it.ssid)) }
    }

    /** dBm for [bars] of 4: Android's calculateSignalLevel maps -100..-55 dBm onto the bars, -48 is "full" (the old fixed value). */
    fun rssiFor(bars: Int): Int = when (bars.coerceIn(1, MAX_BARS)) { 1 -> -84; 2 -> -74; 3 -> -63; else -> -48 }

    fun encode(p: Properties): ByteArray = ByteArrayOutputStream().also { p.store(it, "AEmulator Wi-Fi") }.toByteArray()

    /** Writes (or, when emulation is off or impossible, removes) the settings file of the guest in [root]. */
    fun write(root: File, s: VmSettings, active: Boolean) {
        val f = File(root, FILE)
        if (!active) { f.delete(); return }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(encode(properties(s)))
        tmp.setReadable(true, false)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
