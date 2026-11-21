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
        final String ssid, psk, bssid, security;
        final int rssi, speedKbps;
        Extra(String ssid, String psk, int rssi, String bssid, String security, int speedKbps) {
            this.ssid = ssid; this.psk = psk; this.rssi = rssi; this.bssid = bssid; this.security = security; this.speedKbps = speedKbps;
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
        bssid = p.getProperty("bssid", "02:00:00:00:00:01");
        mac = p.getProperty("mac", "02:15:5d:ae:00:01");
        ip = p.getProperty("ip", "10.0.2.15");
        gateway = p.getProperty("gateway", "10.0.2.2");
        prefix = num(p.getProperty("prefix"), 24);
        dns1 = p.getProperty("dns1", "8.8.8.8");
        dns2 = p.getProperty("dns2", "1.1.1.1");
        rssi = num(p.getProperty("rssi"), -48);
        speed = num(p.getProperty("speed"), 72);
        frequency = num(p.getProperty("frequency"), 2437);
        // "ssid|bssid|caps|rssi|frequency" entries separated by newlines (written by WifiEmu.kt)
        for (String line : p.getProperty("extras", "").split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 4) continue;
            String name = unhex(f[0]);
            if (name.length() == 0) continue;
            String psk = unhex(f[1]);
            String sec = f.length > 4 ? f[4] : psk.length() >= 8 ? "wpa2" : "none";
            if (!"none".equals(sec) && psk.length() < 8) continue;
            extras.add(new Extra(name, "none".equals(sec) ? "" : psk, num(f[2], -60), f[3], sec, f.length > 5 ? Math.max(0, num(f[5], 0)) : 0));
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
