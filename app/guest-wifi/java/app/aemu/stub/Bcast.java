package app.aemu.stub;

import android.content.Intent;

import java.lang.reflect.Method;

/**
 * Sends a broadcast from the stub's own process, on any release: IActivityManager.broadcastIntentWithFeature (12+) or
 * broadcastIntent (older), with the arguments filled by type. Used for SCAN_RESULTS_AVAILABLE once a startScan() is done:
 * Settings and the setup wizard wait for it and otherwise report "Could not find network".
 */
final class Bcast {
    private static int failures;
    private static boolean logged;

    private Bcast() {}

    static void send(Intent intent) {
        send(intent, false);
    }

    /**
     * [sticky]: the broadcast is kept for receivers that register later. The framework sends the Wi-Fi state ones that way, and
     * a screen that opens afterwards gets the last one, so a stale "disabled" left by the framework's own (radio-less) Wi-Fi
     * stack would show the switch off; a sticky one of ours replaces it. Falls back to a plain broadcast when refused.
     */
    static boolean send(Intent intent, boolean sticky) {
        if (sticky) {
            if (send0(intent, true)) return true;
            send0(intent, false);
            return false;
        }
        send0(intent, false);
        return true;
    }

    private static boolean send0(Intent intent, boolean sticky) {
        try {
            Object am = Refl.callStatic(Refl.cls("android.app.ActivityManager"), "getService");
            if (am == null) am = Refl.callStatic(Refl.cls("android.app.ActivityManagerNative"), "getDefault");
            if (am == null) { if (failures++ < 5) Refl.log("broadcast: no activity manager"); return false; }
            Method m = null;
            for (Method c : am.getClass().getMethods()) if (c.getName().equals("broadcastIntentWithFeature")) { m = c; break; }
            if (m == null) for (Method c : am.getClass().getMethods()) if (c.getName().equals("broadcastIntent")) { m = c; break; }
            if (m == null) { if (failures++ < 5) Refl.log("broadcast: no broadcastIntent"); return false; }
            Class<?>[] p = m.getParameterTypes();
            int ints = 0;
            for (Class<?> c : p) if (c == int.class) ints++;
            Object[] a = new Object[p.length];
            int intIdx = 0, boolIdx = 0;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == Intent.class) a[i] = intent;
                else if (p[i] == boolean.class) { boolIdx++; a[i] = Boolean.valueOf(sticky && boolIdx == 2); } // serialized (no), sticky
                else if (p[i] == int.class) { a[i] = Integer.valueOf(ints == 3 && intIdx == 1 ? -1 : 0); intIdx++; } // resultCode, appOp, userId
                else a[i] = null;
            }
            m.invoke(am, a);
            if (!logged) { logged = true; Refl.log("broadcast sent through " + m.getName() + ": " + intent.getAction()); }
            return true;
        } catch (Throwable t) {
            Throwable why = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            if (failures++ < 5) Refl.log("broadcast " + intent.getAction() + (sticky ? " (sticky)" : "") + " failed: " + why);
            return false;
        }
    }
}
