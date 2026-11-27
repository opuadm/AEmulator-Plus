/* AEmulator Plus, 2026-11-25: screen cutout overlays for device skins and default screen density per real device. GPL-3.0. */
package app.aemu.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.roundToInt

/** A camera cutout drawn on top of the guest screen of a device skin (the guest itself never sees it). */
enum class SkinCutout {
    NONE,
    /** iPhone X style: a wide notch hanging from the top edge, rounded at the bottom */
    NOTCH,
    /** Galaxy S20 style: a round hole in the middle of the top edge */
    PUNCH_CENTER,
    /** Galaxy S10 / Pixel 4a style: a round hole in the top left corner */
    PUNCH_LEFT,
    /** Pixel 3 XL / Galaxy A style: a small water drop notch */
    DROP;

    /** Draws the cutout on a screen of [w] x [h] pixels. Sizes follow the screen width, like the real parts do. */
    fun draw(c: Canvas, w: Float, h: Float, paint: Paint) {
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        paint.color = 0xFF000000.toInt()
        when (this) {
            NONE -> {}
            NOTCH -> {
                // iPhone X: the notch is about 56% of the width and 7% (of the width) deep, corners about 1/3 of its depth
                val nw = w * 0.56f; val nh = w * 0.07f; val r = nh * 0.55f
                val l = (w - nw) / 2f
                val p = Path().apply {
                    moveTo(l - r, 0f)
                    // the soft "ears" where the notch meets the top edge
                    quadTo(l, 0f, l, r * 0.6f)
                    lineTo(l, nh - r)
                    quadTo(l, nh, l + r, nh)
                    lineTo(l + nw - r, nh)
                    quadTo(l + nw, nh, l + nw, nh - r)
                    lineTo(l + nw, r * 0.6f)
                    quadTo(l + nw, 0f, l + nw + r, 0f)
                    close()
                }
                c.drawPath(p, paint)
            }
            PUNCH_CENTER -> c.drawCircle(w / 2f, w * 0.04f, w * 0.0155f, paint)
            PUNCH_LEFT -> c.drawCircle(w * 0.075f, w * 0.04f, w * 0.0155f, paint)
            DROP -> {
                val dw = w * 0.17f; val dh = w * 0.045f; val l = (w - dw) / 2f
                val r = dh * 0.9f
                val rect = RectF(l, -r, l + dw, dh)
                c.drawRoundRect(rect, r, r, paint)
            }
        }
    }

    companion object {
        fun parse(v: String?): SkinCutout = runCatching { valueOf(v ?: "") }.getOrDefault(NONE)
    }
}

/**
 * The density (ro.sf.lcd_density) a device skin's guest screen starts with: what the real phone or tablet runs at, so
 * the firmware's own density (made for its old screen) does not make a 1080x2400 screen look like a 480x800 one.
 * Order of choice: the density the skin itself names, the table of real devices below (by the skin's name), then an
 * estimate from the screen size alone. The values are the stock densities of those devices at the resolution of the
 * entry; a skin drawn for another resolution of the same device gets the density scaled to it.
 */
object SkinDensity {
    private class Dev(val name: Regex, val refWidth: Int, val dpi: Int)
    private fun d(re: String, refWidth: Int, dpi: Int) = Dev(Regex(re, RegexOption.IGNORE_CASE), refWidth, dpi)

    // more specific names first: the first match wins
    private val DEVICES = listOf(
        // Apple (3x devices are 480 dpi on the Android scale, 2x ones 320)
        d("""iphone\s*(se|[5-8]\b|[5-8]s|[5-8]\s*plus)""", 750, 320),
        d("""iphone\s*(xr|11\b)""", 828, 320),
        d("""iphone""", 1125, 480),
        d("""ipad""", 1536, 320),            // 2x devices: 320 on the Android scale, whatever the model
        // Google
        d("""nexus\s*10""", 1600, 320), d("""nexus\s*9""", 1536, 320), d("""nexus\s*7""", 1200, 320),
        d("""galaxy\s*nexus""", 720, 320), d("""nexus\s*6p?""", 1440, 560), d("""nexus\s*5x""", 1080, 420),
        d("""nexus\s*5""", 1080, 480), d("""nexus\s*4""", 768, 320), d("""nexus\s*s""", 480, 240), d("""nexus\s*one""", 480, 240),
        d("""pixel\s*(\d+\s*)?(pro|xl)""", 1440, 560), d("""pixel\s*(fold|9\s*pro\s*fold)""", 1080, 420),
        d("""pixel\s*[5]""", 1080, 432), d("""pixel\s*(3a?|4a?)\b""", 1080, 440), d("""pixel\s*[2678]""", 1080, 420), d("""pixel\s*9""", 1080, 420),
        d("""pixel\s*c""", 2560, 320), d("""pixel\s*tablet""", 1600, 276), d("""pixel""", 1080, 420),
        // Samsung (QHD+ models run 560, their FHD+ setting scales to 420)
        d("""galaxy\s*tab""", 1600, 280),
        d("""galaxy\s*s\b""", 480, 240), d("""galaxy\s*(s2|s\s*ii)\b""", 480, 240), d("""galaxy\s*s\s*(3|iii)\b""", 720, 320), d("""galaxy\s*s\s*(4|iv)\b""", 1080, 480),
        d("""galaxy\s*s\s*5\b""", 1080, 432), d("""galaxy\s*note\s*3\b""", 1080, 480), d("""galaxy\s*note\s*4\b""", 1440, 560),
        d("""galaxy\s*note\s*(2|ii)\b""", 720, 320), d("""galaxy\s*note\b""", 800, 320),
        d("""galaxy\s*(s\s*(6|7|8|9|1\d|2\d)|note\s*(5|8|9|10|20))""", 1440, 560),
        d("""galaxy\s*a\s*\d""", 1080, 420), d("""galaxy\s*(z|fold|flip)""", 1080, 420), d("""galaxy""", 1080, 420),
        // HTC
        d("""desire\s*hd""", 480, 240), d("""sensation""", 540, 240), d("""one\s*x""", 720, 320),
        d("""(htc\s*)?one\s*(m[789]|max)""", 1080, 480), d("""htc\s*(10|u1\d)""", 1440, 560),
        // LG, Sony, Motorola
        d("""lg\s*g\s*[34]\b""", 1440, 560), d("""lg\s*g\s*2\b""", 1080, 480), d("""lg\s*v\s*(30|40)""", 1440, 560),
        d("""xperia\s*z[1-5]?\b""", 1080, 480), d("""xperia\s*(1|5)\b""", 1644, 640),
        d("""moto\s*g\s*[67]""", 720, 280), d("""moto\s*g\b""", 720, 320),
        // the rest
        d("""one\s*plus\s*(one|2|x)\b""", 1080, 480), d("""one\s*plus\s*[5-9]\s*(pro|t)""", 1440, 560), d("""one\s*plus""", 1080, 420),
        d("""(redmi|poco|xiaomi|mi\s*\d)""", 1080, 400), d("""(huawei|honor|mate|p\s*\d+\b)""", 1080, 480),
    )

    private val BUCKETS = intArrayOf(120, 160, 213, 240, 264, 276, 280, 320, 360, 400, 420, 432, 440, 480, 520, 560, 640)

    private fun snap(v: Double): Int = BUCKETS.minByOrNull { kotlin.math.abs(it - v) } ?: v.roundToInt()

    /** The density for a skin of [name] whose guest screen is [width] x [height]; null when [width] or [height] is unknown. */
    fun forDevice(name: String, width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val short = minOf(width, height).toDouble()
        val hit = DEVICES.firstOrNull { it.name.containsMatchIn(name) }
        if (hit != null) {
            // the same device drawn at another resolution keeps its physical size: scale the density with the width (a few percent off is the same panel)
            return if (kotlin.math.abs(short / hit.refWidth - 1.0) < 0.08) hit.dpi else snap(hit.dpi * short / hit.refWidth)
        }
        // unknown device: a phone is about 390 dp wide, a tablet about 800 dp
        val long = maxOf(width, height).toDouble()
        val tablet = (short >= 720 && long / short < 1.65) || Regex("""\b(tab|pad|slate|tablet)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)
        return snap(short * 160.0 / if (tablet) 800.0 else 392.0)
    }
}

/** Lies over the guest screen of a device skin: the picture the person chose (stretched over the screen), then the cutout. Never takes a touch. */
class SkinCutoutView(ctx: android.content.Context) : android.view.View(ctx) {
    var cutout: SkinCutout = SkinCutout.NONE
        set(v) { if (field != v) { field = v; invalidate() } }
    var picture: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        picture?.let { c.drawBitmap(it, null, RectF(0f, 0f, w, h), paint) }
        cutout.draw(c, w, h, paint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?) = false
}
