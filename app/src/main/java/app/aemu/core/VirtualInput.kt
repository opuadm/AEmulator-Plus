package app.aemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure logic of the two optional input methods (VM settings › Controls):
 *  - "Mouse and keyboard": an emulated touchpad + keyboard replace the touch screen ([TouchpadProtocol], [KeyboardLatch]);
 *  - "Keypad": feature-phone keys sent through the ordinary key device ([Keypad]); never disables touch.
 *  - "S-Pen": an extra pen digitizer ([SPenProtocol]); a toggle decides whether touches go to it or to the touch screen.
 */
object VirtualKeys {
    const val ESC = 1; const val BACKSPACE = 14; const val TAB = 15; const val ENTER = 28
    const val CTRL = 29; const val SHIFT = 42; const val ALT = 56; const val SPACE = 57; const val CAPS = 58
    const val HOME = 102; const val UP = 103; const val PAGE_UP = 104; const val LEFT = 105; const val RIGHT = 106
    const val END = 107; const val DOWN = 108; const val PAGE_DOWN = 109; const val INSERT = 110; const val DELETE = 111
    const val KPASTERISK = 55; const val SEND = 231; const val ENDCALL = 107
    /** Linux KEY_STAR / KEY_NUMERIC_POUND style codes used by Generic.kl of 5.0+; older layouts map them to nothing, harmless. */
    const val STAR = 522; const val POUND = 523
    const val SELECT = 232
}

/** One key of the floating keyboard. [shifted] is the label shown while Shift is latched. */
data class VKey(val label: String, val code: Int, val weight: Float = 1f, val shifted: String? = null) {
    val isModifier: Boolean get() = code == VirtualKeys.SHIFT || code == VirtualKeys.CTRL || code == VirtualKeys.ALT
}

object VirtualKeyboardLayout {
    private fun k(label: String, code: Int, w: Float = 1f, shifted: String? = null) = VKey(label, code, w, shifted)
    private val numbers = "1234567890"
    private val numShift = "!@#$%^&*()"
    val rows: List<List<VKey>> = listOf(
        listOf(k("Esc", 1, 1.4f)) + numbers.mapIndexed { i, c -> k(c.toString(), if (i == 9) 11 else i + 2, 1f, numShift[i].toString()) } +
            listOf(k("-", 12, 1f, "_"), k("=", 13, 1f, "+"), k("⌫", VirtualKeys.BACKSPACE, 1.5f)),
        listOf(k("Tab", VirtualKeys.TAB, 1.4f)) + "qwertyuiop".mapIndexed { i, c -> k(c.toString(), 16 + i, 1f, c.uppercase()) } +
            listOf(k("[", 26, 1f, "{"), k("]", 27, 1f, "}"), k("\\", 43, 1f, "|")),
        listOf(k("Caps", VirtualKeys.CAPS, 1.7f)) + "asdfghjkl".mapIndexed { i, c -> k(c.toString(), 30 + i, 1f, c.uppercase()) } +
            listOf(k(";", 39, 1f, ":"), k("'", 40, 1f, "\""), k("⏎", VirtualKeys.ENTER, 1.9f)),
        listOf(k("⇧", VirtualKeys.SHIFT, 2.2f)) + "zxcvbnm".mapIndexed { i, c -> k(c.toString(), 44 + i, 1f, c.uppercase()) } +
            listOf(k(",", 51, 1f, "<"), k(".", 52, 1f, ">"), k("/", 53, 1f, "?"), k("↑", VirtualKeys.UP, 1f), k("`", 41, 1f, "~")),
        listOf(k("Ctrl", VirtualKeys.CTRL, 1.6f), k("Alt", VirtualKeys.ALT, 1.4f), k("Home", VirtualKeys.HOME, 1.4f),
            k("␣", VirtualKeys.SPACE, 5f), k("Del", VirtualKeys.DELETE, 1.4f), k("←", VirtualKeys.LEFT), k("↓", VirtualKeys.DOWN), k("→", VirtualKeys.RIGHT)),
    )
}

/**
 * Shift/Ctrl/Alt on a touch keyboard: a tap latches the modifier (held down in the guest) until the next ordinary key
 * has been released, then it is let go. A second tap before that releases it. Caps Lock is an ordinary key.
 */
internal class KeyboardLatch(private val send: (Int, Boolean) -> Unit) {
    private val latched = linkedSetOf<Int>()
    val latchedModifiers: Set<Int> get() = latched
    private val pressed = mutableSetOf<Int>()

    fun tapModifier(code: Int) {
        if (latched.remove(code)) send(code, false) else { latched.add(code); send(code, true) }
    }

    fun down(code: Int) { if (pressed.add(code)) send(code, true) }

    fun up(code: Int) {
        if (!pressed.remove(code)) return
        send(code, false)
        if (latched.isNotEmpty()) { latched.toList().forEach { send(it, false) }; latched.clear() }
    }

    /** Window closed, app backgrounded: nothing may stay held in the guest. */
    fun releaseAll() {
        pressed.toList().forEach { send(it, false) }; pressed.clear()
        latched.toList().forEach { send(it, false) }; latched.clear()
    }
}

/** Buttons of the feature-phone keypad window. */
data class PadKey(val label: String, val code: Int, val sub: String = "")

object Keypad {
    val softLeft = PadKey("◀ Menu", InputService.KEY_MENU)
    val softRight = PadKey("Back ▶", InputService.KEY_BACK)
    val call = PadKey("Call", VirtualKeys.SEND)
    val end = PadKey("End", VirtualKeys.ENDCALL)
    val up = PadKey("▲", VirtualKeys.UP)
    val down = PadKey("▼", VirtualKeys.DOWN)
    val left = PadKey("◀", VirtualKeys.LEFT)
    val right = PadKey("▶", VirtualKeys.RIGHT)
    val ok = PadKey("OK", VirtualKeys.SELECT)
    /** 1-9, then * 0 #, as on a phone keypad. */
    val digits: List<PadKey> = listOf(
        PadKey("1", 2), PadKey("2", 3, "abc"), PadKey("3", 4, "def"),
        PadKey("4", 5, "ghi"), PadKey("5", 6, "jkl"), PadKey("6", 7, "mno"),
        PadKey("7", 8, "pqrs"), PadKey("8", 9, "tuv"), PadKey("9", 10, "wxyz"),
        PadKey("*", VirtualKeys.STAR), PadKey("0", 11, "+"), PadKey("#", VirtualKeys.POUND),
    )
}

/**
 * Touchpad frames (multi-touch protocol A, the one every Android from 3.2 reads). The device is a touchpad, not a
 * screen: the guest's own gesture code turns taps into clicks and two fingers into scrolling. Coordinates are
 * normalised to the pad range, so the whole guest screen is the pad.
 */
class TouchpadProtocol {
    data class Contact(val id: Int, val x: Int, val y: Int)

    private var down = false
    private var last: List<Contact> = emptyList()

    /** Events for the new contact set, or an empty array when nothing changed. */
    fun frame(now: List<Contact>, nanos: Long): ByteArray {
        if (now.isEmpty() && !down) return ByteArray(0)
        if (now.isNotEmpty() && now == last) return ByteArray(0)
        val b = ByteBuffer.allocate((now.size * 6 + 6) * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, code: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        }
        for (c in now) {
            put(EV_ABS, ABS_MT_TRACKING_ID, c.id.coerceIn(0, 31))
            put(EV_ABS, ABS_MT_POSITION_X, c.x.coerceIn(0, PAD_MAX))
            put(EV_ABS, ABS_MT_POSITION_Y, c.y.coerceIn(0, PAD_MAX))
            put(EV_ABS, ABS_MT_TOUCH_MAJOR, 40)
            put(EV_ABS, ABS_MT_PRESSURE, 64)
            put(EV_SYN, SYN_MT_REPORT, 0)
        }
        val isDown = now.isNotEmpty()
        if (isDown != down) { put(EV_KEY, BTN_TOUCH, if (isDown) 1 else 0); down = isDown }
        if (!isDown) put(EV_SYN, SYN_MT_REPORT, 0)
        put(EV_SYN, SYN_REPORT, 0)
        last = now
        return b.array().copyOf(b.position())
    }

    /** A click on the pad's own button (BTN_LEFT / BTN_RIGHT). */
    fun button(code: Int, pressed: Boolean, nanos: Long): ByteArray {
        val b = ByteBuffer.allocate(2 * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, c: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(c.toShort()).putInt(value)
        }
        put(EV_KEY, code, if (pressed) 1 else 0); put(EV_SYN, SYN_REPORT, 0)
        return b.array()
    }

    companion object {
        const val PAD_MAX = 4095
        const val BTN_LEFT = 272
        const val BTN_RIGHT = 273
        private const val EV = 16
        private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_ABS = 3
        private const val SYN_REPORT = 0; private const val SYN_MT_REPORT = 2
        private const val ABS_MT_TOUCH_MAJOR = 48; private const val ABS_MT_POSITION_X = 53
        private const val ABS_MT_POSITION_Y = 54; private const val ABS_MT_TRACKING_ID = 57; private const val ABS_MT_PRESSURE = 58
        private const val BTN_TOUCH = 330

        fun pad(v: Int, size: Int): Int = if (size <= 1) 0 else (v.toLong() * PAD_MAX / (size - 1)).toInt().coerceIn(0, PAD_MAX)
    }
}

/**
 * The emulated S-Pen digitizer (input method "S-Pen"): what the guest's EventHub reads from the evdev node named sec_e-pen.
 * Single-touch "Wacom" protocol, the one Samsung's Note digitizers speak: ABS_X/ABS_Y, ABS_PRESSURE, ABS_DISTANCE, BTN_TOUCH,
 * BTN_TOOL_PEN (pen in range) and BTN_STYLUS (side button). A pen is one contact: of several fingers down the first one is
 * the pen, and when it lifts the others are ignored until every finger is up, so the pen never jumps to another finger.
 * Like a real pen it hovers before it touches and after it lifts: tool in range, then contact; contact ends, then the tool
 * leaves. [hover] reports the pen in range without contact, and the side button can be pressed while it hovers: that is what
 * opens Samsung's Air Command (the guest's SpenGestureManagerService watches for a stylus hover whose side button goes down
 * and up again within 800 ms without a touch in between). Keep the axis ranges in step with native/guestshim/vinput.h and
 * the runner's spen_ioctl().
 */
class SPenProtocol {
    /** [x] and [y] are 0..[XY_MAX], [pressure] 1..[PRESSURE_MAX] (see [axis] and [pressureOf]). */
    data class Contact(val id: Int, val x: Int, val y: Int, val pressure: Int)

    private var trackId = -1
    private var armed = true          // may a new pen contact start? false from the moment the pen lifts until every finger is up
    private var inRange = false       // BTN_TOOL_PEN
    private var touching = false      // BTN_TOUCH
    private var side = false          // BTN_STYLUS
    private var lastX = -1
    private var lastY = -1
    private var lastPressure = -1

    /** Events for the new set of host contacts, or an empty array when nothing changed. */
    fun frame(now: List<Contact>, button: Boolean, nanos: Long): ByteArray {
        if (now.isEmpty()) armed = true
        val pen: Contact? = when {
            trackId >= 0 -> now.firstOrNull { it.id == trackId }.also { if (it == null) { trackId = -1; armed = now.isEmpty() } }
            armed && now.isNotEmpty() -> now[0].also { trackId = it.id; armed = false }
            else -> null
        }
        val b = ByteBuffer.allocate(24 * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, code: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        }
        if (pen == null) {
            if (!inRange) return ByteArray(0)
            // contact ends first (still hovering), then the pen leaves the digitizer's range
            if (touching) {
                if (side) { put(EV_KEY, BTN_STYLUS, 0); side = false }
                put(EV_ABS, ABS_PRESSURE, 0); put(EV_ABS, ABS_DISTANCE, HOVER_DISTANCE); put(EV_KEY, BTN_TOUCH, 0); put(EV_SYN, SYN_REPORT, 0)
                touching = false
            }
            if (side) { put(EV_KEY, BTN_STYLUS, 0); side = false }
            put(EV_ABS, ABS_DISTANCE, 0); put(EV_KEY, BTN_TOOL_PEN, 0); put(EV_SYN, SYN_REPORT, 0)
            inRange = false; lastX = -1; lastY = -1; lastPressure = -1
            return b.array().copyOf(b.position())
        }
        val x = pen.x.coerceIn(0, XY_MAX); val y = pen.y.coerceIn(0, XY_MAX)
        val p = pen.pressure.coerceIn(1, PRESSURE_MAX)
        if (!inRange) {
            put(EV_ABS, ABS_X, x); put(EV_ABS, ABS_Y, y); put(EV_ABS, ABS_PRESSURE, 0); put(EV_ABS, ABS_DISTANCE, HOVER_DISTANCE)
            put(EV_KEY, BTN_TOOL_PEN, 1); put(EV_SYN, SYN_REPORT, 0)
            inRange = true; lastX = x; lastY = y; lastPressure = 0
        }
        if (!touching) {
            // the pen may have been hovering somewhere else (see hover) and may already hold its button
            if (x != lastX) put(EV_ABS, ABS_X, x)
            if (y != lastY) put(EV_ABS, ABS_Y, y)
            put(EV_ABS, ABS_PRESSURE, p); put(EV_ABS, ABS_DISTANCE, 0); put(EV_KEY, BTN_TOUCH, 1)
            if (button != side) put(EV_KEY, BTN_STYLUS, if (button) 1 else 0)
            put(EV_SYN, SYN_REPORT, 0)
            touching = true; side = button; lastPressure = p
        } else {
            val moved = x != lastX || y != lastY
            if (!moved && p == lastPressure && button == side) return ByteArray(0)
            if (x != lastX) put(EV_ABS, ABS_X, x)
            if (y != lastY) put(EV_ABS, ABS_Y, y)
            if (p != lastPressure) put(EV_ABS, ABS_PRESSURE, p)
            if (button != side) { put(EV_KEY, BTN_STYLUS, if (button) 1 else 0); side = button }
            put(EV_SYN, SYN_REPORT, 0)
            lastPressure = p
        }
        lastX = x; lastY = y
        return b.array().copyOf(b.position())
    }

    /** Is the pen currently touching the screen (a contact started by [frame])? */
    val isTouching: Boolean get() = touching

    /**
     * The pen in range but not touching, at ([x], [y]) on the digitizer's axes, with its side [button] up or down. The first
     * call brings the tool into range; later calls send only what changed (position, button). Nothing while a contact is in
     * progress: the contact owns the pen. End the hover with `frame(emptyList(), false, ...)`, which takes the tool out of range.
     */
    fun hover(x0: Int, y0: Int, button: Boolean, nanos: Long): ByteArray {
        if (touching) return ByteArray(0)
        val x = x0.coerceIn(0, XY_MAX); val y = y0.coerceIn(0, XY_MAX)
        val first = !inRange
        if (!first && x == lastX && y == lastY && button == side) return ByteArray(0)
        val b = ByteBuffer.allocate(8 * EV).order(ByteOrder.LITTLE_ENDIAN)
        fun put(type: Int, code: Int, value: Int) {
            b.putInt((nanos / 1_000_000_000).toInt()).putInt(((nanos % 1_000_000_000) / 1000).toInt())
            b.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        }
        if (first) {
            put(EV_ABS, ABS_X, x); put(EV_ABS, ABS_Y, y); put(EV_ABS, ABS_PRESSURE, 0); put(EV_ABS, ABS_DISTANCE, HOVER_DISTANCE)
            put(EV_KEY, BTN_TOOL_PEN, 1)
            if (button) put(EV_KEY, BTN_STYLUS, 1)
            inRange = true; lastPressure = 0
        } else {
            if (x != lastX) put(EV_ABS, ABS_X, x)
            if (y != lastY) put(EV_ABS, ABS_Y, y)
            if (button != side) put(EV_KEY, BTN_STYLUS, if (button) 1 else 0)
        }
        put(EV_SYN, SYN_REPORT, 0)
        side = button; lastX = x; lastY = y
        return b.array().copyOf(b.position())
    }

    companion object {
        const val XY_MAX = 16383
        const val PRESSURE_MAX = 4095
        const val DISTANCE_MAX = 255
        const val TILT_MAX = 63
        /** ABS_DISTANCE reported while the pen only hovers (just before touching and just after lifting). */
        const val HOVER_DISTANCE = 24
        const val NODE_NAME = "sec_e-pen"
        /**
         * One click of the pen's side button as a series of hover frames: [CLICK_STEPS] holds the button down for
         * (steps * [CLICK_STEP_MS]) ms, which is inside the 0..800 ms window the guest accepts and long enough for the guest
         * to see a hover move that carries the button. Used by [InputService.penButtonClick].
         */
        const val CLICK_STEP_MS = 45L
        const val CLICK_STEPS = 4
        private const val EV = 16
        private const val EV_SYN = 0; private const val EV_KEY = 1; private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val ABS_X = 0; private const val ABS_Y = 1; private const val ABS_PRESSURE = 24; private const val ABS_DISTANCE = 25
        private const val BTN_TOOL_PEN = 320; private const val BTN_TOUCH = 330; private const val BTN_STYLUS = 331

        /** A position inside a view [size] pixels across (0 at the first pixel, [size] at the far edge) on the digitizer's axis. */
        fun axis(v: Float, size: Int): Int = if (size <= 0) 0 else Math.round(v / size * XY_MAX).coerceIn(0, XY_MAX)

        /**
         * Host pressure (0..1) on the digitizer's 0..4095 scale. A finger on most phones reports a constant 1.0 or nothing useful,
         * which would draw every stroke with the heaviest line a pen can make: a finger is mapped into the middle of the range
         * (0.15..0.75), a real stylus on the host keeps its whole range.
         */
        fun pressureOf(host: Float, stylus: Boolean): Int {
            val h = if (host.isNaN()) 1f else host.coerceIn(0f, 1f)
            val level = if (stylus) h else 0.15f + 0.6f * h
            return Math.round(level * PRESSURE_MAX).coerceIn(1, PRESSURE_MAX)
        }
    }
}

/** What the guest must provide for [VmSettings.mouseKeyboard]; the keypad needs nothing but the ordinary key device. */
object VirtualInputPolicy {
    /**
     * The emulated evdev nodes (aemu-touchpad, aemu-keyboard) come from the guest shim on the qemu engines (libaemushim,
     * ARM and x86-32 builds) and from the runner's own input emulation on the native arm64 engine (libaemu64_*, any
     * firmware architecture). Recovery and framework-less boots need none. A pre-1.0 build (see [LegacyFirmware]) has no
     * support for it at all: it gets the one combined keypad/keyboard window instead (see [LegacyKeys.rows]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun mouseKeyboardSupported(engine: Engine, arch: GuestArch, recovery: Boolean, nativeUi: Boolean, preRelease: Boolean = false): Boolean =
        !recovery && !nativeUi && !preRelease   // a pre-1.0 build has neither the guest shim's nodes nor a pad-aware input stack

    /**
     * The S-Pen digitizer ([SPenProtocol]) needs the same nodes as the mouse and keyboard, plus a guest that knows stylus
     * tools: Android 4.0 (API 14) added TOOL_TYPE_STYLUS and BTN_TOOL_PEN handling. Older builds would see a second touch screen.
     */
    fun spenSupported(recovery: Boolean, nativeUi: Boolean, preRelease: Boolean, api: Int): Boolean =
        !recovery && !nativeUi && !preRelease && api >= 14
}
