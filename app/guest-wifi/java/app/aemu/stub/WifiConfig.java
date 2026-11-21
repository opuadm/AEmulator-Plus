/* Modified for AEmulator Super Plus, 2026-10-11: neighbouring networks and signal level. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.stub;

import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.util.Properties;

/** The emulated network, as the host wrote it to /data/system/aemu-wifi.properties (see WifiEmu.kt). */
final class WifiConfig {
    static final String FILE = "/data/system/aemu-wifi.properties";

    final String ssid;
    final boolean secured;
    /** "none", "wpa2", "wpa" (WPA/WPA2 mixed) or "wpa3". */
    final String security;
    /** Connect to [ssid] at start-up; off = the guest starts disconnected and the user joins from Settings. */
    final boolean autoConnect;
    /** Speed limit of the first network in kbit/s; 0 = none. */
    final int speedKbps;
    final long modified;
    final String psk;
    final String bssid;
    final String base;
    final String band;
    final String standard;
    final String mac;
    final String ip;
    final String gateway;
    final int prefix;
    final String dns1;
    final String dns2;
    final int rssi;
    final int speed;
    final int frequency;
    /** Another joinable network of the host's list: its own name, passphrase (empty = open), signal and BSSID. */
    static final class Extra {
        final String ssid, psk, bssid, base, security, band, standard;
        final int rssi, speedKbps;
        Extra(String ssid, String psk, int rssi, String base, String security, int speedKbps, String band, String standard) {
            this.ssid = ssid; this.psk = psk; this.rssi = rssi; this.base = base; this.security = security; this.speedKbps = speedKbps;
            this.band = bandOf(band); this.standard = standard; this.bssid = connBssid(this.band, base);
        }
        boolean secured() { return !"none".equals(security); }
    }

    /** The capabilities string a scan result carries for a network of this security type. */
    static String caps(String security) {
        if ("wpa3".equals(security)) return "[RSN-SAE-CCMP][ESS]";
        if ("wpa".equals(security)) return "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]";
        if ("wpa2".equals(security)) return "[WPA2-PSK-CCMP][ESS]";
        return "[ESS]";
    }
    /** The band as the guest can show it: 6 GHz from Android 12 (API 31) on; "24", "5", "dual" (2.4 + 5) or "6". */
    static String bandOf(String b) {
        if ("5".equals(b) || "dual".equals(b)) return b;
        if ("6".equals(b)) return android.os.Build.VERSION.SDK_INT >= 31 ? "6" : "5";
        return "24";
    }

    static int[] freqs(String band) {
        if ("5".equals(band)) return new int[] { 5180 };
        if ("6".equals(band)) return new int[] { 5955 };
        if ("dual".equals(band)) return new int[] { 2437, 5180 };
        return new int[] { 2437 };
    }

    /** The frequency a client joins on: the fastest band of the network. */
    static int connFreq(String band) {
        int[] f = freqs(band);
        return f[f.length - 1];
    }

    /** The second radio of a dual-band network answers under its own BSSID. */
    static String altBssid(String b) {
        int i = b.lastIndexOf(':');
        if (i < 0 || i + 3 > b.length()) return b;
        try {
            return b.substring(0, i + 1) + String.format("%02x", (Integer.parseInt(b.substring(i + 1, i + 3), 16) ^ 0x01) & 0xff);
        } catch (Throwable t) {
            return b;
        }
    }

    static String bssidAt(String band, int freq, String base) {
        return "dual".equals(band) && freq >= 5000 ? altBssid(base) : base;
    }

    static String connBssid(String band, String base) {
        return bssidAt(band, connFreq(band), base);
    }

    /** WifiInfo / ScanResult standard code (Android 11+): 4 = 11n, 5 = 11ac, 6 = 11ax, 8 = 11be (Android 13+); "auto" follows the band. */
    static int standardCode(String standard, String band) {
        if ("be".equals(standard)) return android.os.Build.VERSION.SDK_INT >= 33 ? 8 : 6;
        if ("ax".equals(standard)) return 6;
        if ("ac".equals(standard)) return 5;
        if ("n".equals(standard)) return 4;
        int f = connFreq(band);
        return f >= 5925 ? 6 : f >= 5000 ? 5 : 4;
    }

    final java.util.List<Extra> extras = new java.util.ArrayList<Extra>();

    private static String unhex(String h) {
        try {
            byte[] b = new byte[h.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private WifiConfig(Properties p, long modified) {
        this.modified = modified;
        autoConnect = !"false".equals(p.getProperty("autoconnect"));
        speedKbps = Math.max(0, num(p.getProperty("speedkbps"), 0));
        ssid = p.getProperty("ssid", "AEmulator-WiFi");
        secured = "true".equals(p.getProperty("secured")) && p.getProperty("psk", "").length() >= 8;
        psk = secured ? p.getProperty("psk", "") : "";
        String mainSec = p.getProperty("security", secured ? "wpa2" : "none");
        security = secured ? mainSec : "none";
        band = bandOf(p.getProperty("band", "24"));
        standard = p.getProperty("standard", "auto");
        base = p.getProperty("bssid", "02:00:00:00:00:01");
        bssid = connBssid(band, base);
        mac = p.getProperty("mac", "02:15:5d:ae:00:01");
        ip = p.getProperty("ip", "10.0.2.15");
        gateway = p.getProperty("gateway", "10.0.2.2");
        prefix = num(p.getProperty("prefix"), 24);
        dns1 = p.getProperty("dns1", "8.8.8.8");
        dns2 = p.getProperty("dns2", "1.1.1.1");
        rssi = num(p.getProperty("rssi"), -48);
        speed = num(p.getProperty("speed"), 72);
        frequency = connFreq(band);
        // "ssid|psk|rssi|bssid|security|speed|band|standard" entries separated by newlines (written by WifiEmu.kt)
        for (String line : p.getProperty("extras", "").split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 4) continue;
            String name = unhex(f[0]);
            if (name.length() == 0) continue;
            String psk = unhex(f[1]);
            String sec = f.length > 4 ? f[4] : psk.length() >= 8 ? "wpa2" : "none";
            if (!"none".equals(sec) && psk.length() < 8) continue;
            extras.add(new Extra(name, "none".equals(sec) ? "" : psk, num(f[2], -60), f[3], sec, f.length > 5 ? Math.max(0, num(f[5], 0)) : 0,
                    f.length > 6 ? f[6] : "24", f.length > 7 ? f[7] : "auto"));
        }
    }

    private static int num(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return d; }
    }

    static WifiConfig load() {
        return load(new File(FILE));
    }

    static WifiConfig load(File f) {
        if (!f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            Properties p = new Properties();
            p.load(in);
            return new WifiConfig(p, f.lastModified());
        } catch (Throwable t) {
            Refl.log("cannot read " + f + ": " + t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** IPv4 text as the int the framework uses in DhcpInfo and WifiInfo (first octet in the lowest byte). */
    static int ipInt(String s) {
        try {
            byte[] b = InetAddress.getByName(s).getAddress();
            return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
        } catch (Throwable t) {
            return 0;
        }
    }

    static int netmaskInt(int prefix) {
        int m = prefix <= 0 ? 0 : (-1 << (32 - Math.min(prefix, 32)));
        return Integer.reverseBytes(m);
    }
}
