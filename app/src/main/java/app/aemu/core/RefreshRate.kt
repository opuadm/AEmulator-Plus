/* Added for AEmulator Plus, 2026-11-20. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.view.WindowManager

/**
 * What the guest's screen refresh rate setting (VmSettings.fbHz) can hold: [HOST] = the phone's own maximum, any other
 * positive number = that many Hz (a slider preset or a custom amount).
 *
 * Two rates come out of it ([Resolved]):
 *  - reported: what the guest believes its display runs at (the vsync period of the composer's display mode, so
 *    Display.getRefreshRate() and every app's frame pacing see it);
 *  - tick: what the composer's vsync actually ticks at, which is the rate frames are produced at. It is the reported rate,
 *    but never above what the phone's display can show: a guest set to 144 Hz on a 120 Hz phone reports 144 and renders
 *    at 120.
 * Only the in-app GPU bridge can keep up with more than 60 Hz; SwiftShader draws on the CPU, so without the bridge the
 * guest stays at 60 Hz whatever is chosen.
 */
object RefreshRate {
    const val HOST = -1

    /** The fixed steps of the slider before "host" and "custom". */
    val PRESETS_HZ = listOf(30, 45, 50, 60, 75, 90, 120, 144, 175, 180, 185)

    const val DEFAULT_HZ = 60
    const val CUSTOM_MIN_HZ = 10
    const val CUSTOM_MAX_HZ = 1000

    data class Resolved(val reported: Int, val tick: Int) {
        /** the guest reports a rate the phone cannot show */
        val capped: Boolean get() = tick < reported
    }

    /** The highest refresh rate of the phone's display, in Hz (60 when it cannot be read). */
    @Suppress("DEPRECATION")
    fun hostHz(ctx: Context): Int = runCatching {
        val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        var best = d.refreshRate
        for (m in d.supportedModes) if (m.refreshRate > best) best = m.refreshRate
        Math.round(best)
    }.getOrDefault(DEFAULT_HZ).coerceIn(CUSTOM_MIN_HZ, CUSTOM_MAX_HZ)

    /**
     * [chosen] is the stored setting, [host] the phone's maximum, [gpuBridge] whether the guest draws on the phone's GPU.
     * Anything not a usable rate (0, an old value) means 60.
     */
    fun resolve(chosen: Int, host: Int, gpuBridge: Boolean): Resolved {
        if (!gpuBridge) return Resolved(DEFAULT_HZ, DEFAULT_HZ)
        val reported = when {
            chosen == HOST -> host
            chosen in CUSTOM_MIN_HZ..CUSTOM_MAX_HZ -> chosen
            else -> DEFAULT_HZ
        }
        return Resolved(reported, minOf(reported, host.coerceAtLeast(CUSTOM_MIN_HZ)))
    }

    /** The old fixed-rate engines have no host to follow: a positive value as it is, anything else 60. */
    fun legacyHz(chosen: Int): Int = if (chosen in CUSTOM_MIN_HZ..CUSTOM_MAX_HZ) chosen else DEFAULT_HZ
}
