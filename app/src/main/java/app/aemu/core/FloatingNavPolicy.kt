/* Added for AEmulator Plus, 2026-11-24. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.core

/**
 * The mini floating button bar: the VM's navigation bar, held for a moment and then moved away, becomes a small pill
 * like the menu button. Pure geometry and storage rules for it, so they can be tested without a screen.
 *
 * The pill's position is kept as the fractions (0..1) of the area it may travel in that its centre sits at, so a
 * rotation, another window size or a changing inset never leaves it off screen.
 */
internal object FloatingNavPolicy {
    /** How far (dp) the finger has to move, after the hold, before the bar comes loose. */
    const val DETACH_DP = 20f

    /** True once the finger has travelled farther than [thresholdPx] from where it went down. */
    fun detaches(dx: Float, dy: Float, thresholdPx: Float) = kotlin.math.hypot(dx, dy) > thresholdPx

    /**
     * [fx],[fy] (centre fractions) pulled back so a pill of [w] x [h] px stays completely inside an area of
     * [areaW] x [areaH] px. An area not measured yet (0) keeps the fractions (inside 0..1); an area smaller than the
     * pill centres it. NaN/infinity from a damaged saved value falls back to the middle.
     */
    fun clampFraction(fx: Float, fy: Float, areaW: Int, areaH: Int, w: Int, h: Int): Pair<Float, Float> =
        axis(fx, areaW, w) to axis(fy, areaH, h)

    private fun axis(f: Float, area: Int, size: Int): Float {
        val v = if (f.isFinite()) f else 0.5f
        if (area <= 0) return v.coerceIn(0f, 1f)
        if (size >= area) return 0.5f
        val lo = size / 2f / area
        return v.coerceIn(lo, 1f - lo)
    }

    /** Top-left corner (px, inside the area) of a pill of [w] x [h] whose centre is at the fractions. */
    fun topLeft(fx: Float, fy: Float, areaW: Int, areaH: Int, w: Int, h: Int): Pair<Float, Float> {
        val (cx, cy) = clampFraction(fx, fy, areaW, areaH, w, h)
        return (cx * areaW - w / 2f).coerceAtLeast(0f) to (cy * areaH - h / 2f).coerceAtLeast(0f)
    }

    /** The fractions of a centre at ([cx],[cy]) px in an area of [areaW] x [areaH] px (0.5 when the area is not measured). */
    fun fractionOf(cx: Float, cy: Float, areaW: Int, areaH: Int): Pair<Float, Float> =
        (if (areaW > 0) cx / areaW else 0.5f) to (if (areaH > 0) cy / areaH else 0.5f)

    /** "1,fx,fy" (floating) or "0,fx,fy" (docked; the spot is kept for the next time it is floated). */
    fun encode(floating: Boolean, fx: Float, fy: Float) = "${if (floating) 1 else 0},$fx,$fy"

    /** Inverse of [encode]; null for anything missing or damaged. */
    fun decode(text: String?): Triple<Boolean, Float, Float>? {
        val p = text?.split(',') ?: return null
        if (p.size != 3 || (p[0] != "0" && p[0] != "1")) return null
        val fx = p[1].toFloatOrNull() ?: return null
        val fy = p[2].toFloatOrNull() ?: return null
        if (!fx.isFinite() || !fy.isFinite()) return null
        return Triple(p[0] == "1", fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
    }
}
