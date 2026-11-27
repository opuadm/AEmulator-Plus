/* Added for AEmulator Plus, 2026-11-24. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.aemu.core.FloatingNavPolicy

/**
 * "Hold, then move it away": a finger that stays put for the long-press time and then travels
 * [FloatingNavPolicy.DETACH_DP] grabs whatever carries this modifier (the VM's button bar, or the floating mini bar).
 *
 * The keys under the finger keep working: the gesture is only watched (on the initial pass, nothing is consumed) until the
 * grab, so a tap or an ordinary long press on a key reaches the guest as before, and a hold that never moves is left alone.
 * At the grab the events are consumed from then on, which makes the keys under the finger let go (their own press
 * detectors see a cancelled touch and release the guest key).
 *
 * This modifier has to stay in the tree for the whole drag: [onGrab] is allowed to remove the content it sits on (the bar
 * docked at the edge disappears and becomes the floating one), but not the node carrying the modifier.
 *
 * Positions are reported in the root's coordinates, so they stay valid while the node itself resizes or moves.
 * @param enabled read at the start of every gesture, so switching it never cancels a drag in progress
 * @param excluded a root-space region where a hold does not count (the trackball, which rolls under a held finger)
 */
@Composable
internal fun Modifier.holdAndDrag(
    enabled: Boolean,
    excluded: () -> Rect? = { null },
    onGrab: (finger: Offset) -> Unit,
    onDrag: (delta: Offset) -> Unit,
    onRelease: () -> Unit,
): Modifier {
    val view = LocalView.current
    val config = LocalViewConfiguration.current
    val density = LocalDensity.current
    val enabledNow by rememberUpdatedState(enabled)
    val excludedNow by rememberUpdatedState(excluded)
    val grab by rememberUpdatedState(onGrab)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this.onGloballyPositioned { coordinates.value = it }.pointerInput(Unit) {
        val detachPx = with(density) { FloatingNavPolicy.DETACH_DP.dp.toPx() }
        fun toRoot(p: Offset): Offset = coordinates.value?.takeIf { it.isAttached }?.localToRoot(p) ?: p
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabledNow) return@awaitEachGesture
            val start = toRoot(down.position)
            if (excludedNow()?.contains(start) == true) return@awaitEachGesture

            // 1) the hold: it ends the watching if the finger lifts or wanders (a scroll, a swipe) before the time is up
            var abandoned = false
            withTimeoutOrNull(config.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed ||
                        (toRoot(change.position) - start).getDistance() > config.touchSlop) { abandoned = true; break }
                }
            }
            if (abandoned) return@awaitEachGesture

            // 2) held: moving far enough grabs it, from then on the finger drags it
            var grabbed = false
            var last = start
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (grabbed) { change.consume(); release() }
                    break
                }
                val at = toRoot(change.position)
                if (!grabbed) {
                    if (FloatingNavPolicy.detaches(at.x - start.x, at.y - start.y, detachPx)) {
                        grabbed = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        last = at
                        grab(at)
                    }
                } else {
                    val delta = at - last
                    last = at
                    if (delta != Offset.Zero) drag(delta)
                }
                if (grabbed) event.changes.forEach { it.consume() }
            }
        }
    }
}
