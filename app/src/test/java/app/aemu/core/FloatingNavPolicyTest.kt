package app.aemu.core

import org.junit.Assert.*
import org.junit.Test

class FloatingNavPolicyTest {
    @Test fun detachNeedsRealMovement() {
        assertFalse(FloatingNavPolicy.detaches(3f, 4f, 20f))
        assertFalse(FloatingNavPolicy.detaches(0f, 0f, 20f))
        assertTrue(FloatingNavPolicy.detaches(30f, 0f, 20f))
        assertTrue(FloatingNavPolicy.detaches(-15f, 15f, 20f))
    }
    // area 400x800, pill 200x40: the centre may sit between 25%..75% across and 2.5%..97.5% down
    @Test fun pillStaysInsideTheArea() {
        fun check(fx: Float, fy: Float, ex: Float, ey: Float) {
            val (x, y) = FloatingNavPolicy.clampFraction(fx, fy, 400, 800, 200, 40)
            assertEquals(ex, x, 1e-5f); assertEquals(ey, y, 1e-5f)
        }
        check(0.5f, 0.5f, 0.5f, 0.5f)
        check(-1f, -1f, 0.25f, 0.025f)
        check(2f, 2f, 0.75f, 0.975f)
    }
    @Test fun damagedAndUnmeasuredValuesAreSafe() {
        assertEquals(0.5f to 0.5f, FloatingNavPolicy.clampFraction(Float.NaN, Float.POSITIVE_INFINITY, 400, 800, 200, 40))
        assertEquals(0.3f to 1f, FloatingNavPolicy.clampFraction(0.3f, 5f, 0, 0, 200, 40))
        assertEquals(0.5f to 0.5f, FloatingNavPolicy.clampFraction(0.1f, 0.9f, 100, 30, 200, 40))
    }
    @Test fun topLeftFollowsTheCentre() {
        fun check(fx: Float, fy: Float, ex: Float, ey: Float) {
            val (x, y) = FloatingNavPolicy.topLeft(fx, fy, 400, 800, 200, 40)
            assertEquals(ex, x, 0.01f); assertEquals(ey, y, 0.01f)
        }
        check(0.5f, 0.5f, 100f, 380f)
        check(0f, 0f, 0f, 0f)
        check(1f, 1f, 200f, 760f)
    }
    @Test fun fractionsOfAPixelCentre() {
        assertEquals(0.25f to 0.5f, FloatingNavPolicy.fractionOf(100f, 400f, 400, 800))
        assertEquals(0.5f to 0.5f, FloatingNavPolicy.fractionOf(100f, 400f, 0, 0))
    }
    @Test fun storageRoundTripsAndRejectsDamage() {
        assertEquals(Triple(true, 0.25f, 0.9f), FloatingNavPolicy.decode(FloatingNavPolicy.encode(true, 0.25f, 0.9f)))
        assertEquals(Triple(false, 0.5f, 0.5f), FloatingNavPolicy.decode(FloatingNavPolicy.encode(false, 0.5f, 0.5f)))
        assertNull(FloatingNavPolicy.decode(null))
        assertNull(FloatingNavPolicy.decode("1,0.5"))
        assertNull(FloatingNavPolicy.decode("2,0.5,0.5"))
        assertNull(FloatingNavPolicy.decode("1,NaN,0.5"))
        assertEquals(Triple(true, 1f, 0f), FloatingNavPolicy.decode("1,7,-3"))
    }
}
