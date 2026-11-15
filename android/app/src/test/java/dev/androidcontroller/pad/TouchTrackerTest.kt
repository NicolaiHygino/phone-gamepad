package dev.androidcontroller.pad

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchTrackerTest {
    private val layout = PadLayout(1600f, 720f)
    private val c = layout.dpadCenter
    private val r = layout.dpadRadius

    private fun buttons(vararg fingers: Pair<Long, Offset>) =
        TouchTracker(layout).update(mapOf(*fingers)).buttons

    private fun button(bit: Int) = layout.buttons.first { it.bit == bit }

    @Test
    fun dpadGivesEightDirections() {
        val cases = listOf(
            Offset(0.6f * r, 0f) to Btn.DPAD_RIGHT,
            Offset(-0.6f * r, 0f) to Btn.DPAD_LEFT,
            Offset(0f, 0.6f * r) to Btn.DPAD_DOWN,
            Offset(0f, -0.6f * r) to Btn.DPAD_UP,
            Offset(0.5f * r, 0.5f * r) to (Btn.DPAD_RIGHT or Btn.DPAD_DOWN),
            Offset(-0.5f * r, 0.5f * r) to (Btn.DPAD_LEFT or Btn.DPAD_DOWN),
            Offset(0.5f * r, -0.5f * r) to (Btn.DPAD_RIGHT or Btn.DPAD_UP),
            Offset(-0.5f * r, -0.5f * r) to (Btn.DPAD_LEFT or Btn.DPAD_UP),
        )
        for ((delta, expected) in cases) assertEquals("at $delta", expected, buttons(1L to c + delta))
    }

    @Test
    fun dpadCentreIsDeadzone() {
        assertEquals(0, buttons(1L to c + Offset(3f, -3f)))
    }

    @Test
    fun dpadFingerKeepsControlWhenSlidingOutsideTheRing() {
        val t = TouchTracker(layout)
        t.update(mapOf(1L to c + Offset(0.5f * r, 0f)))
        // dragged far to the right, beyond the ring and toward the face buttons
        val far = t.update(mapOf(1L to c + Offset(3f * r, 0f)))
        assertEquals(Btn.DPAD_RIGHT, far.buttons)
    }

    @Test
    fun eachFaceButtonIsPressedAtItsCentre() {
        for (b in layout.buttons) assertEquals(b.caption, b.bit, buttons(1L to b.center))
    }

    @Test
    fun oneThumbBetweenTwoFaceButtonsPressesBoth() {
        val mid = (button(Btn.A).center + button(Btn.B).center) / 2f
        assertEquals(Btn.A or Btn.B, buttons(1L to mid))
    }

    @Test
    fun twoThumbsWorkIndependently() {
        val state = buttons(1L to c + Offset(0.6f * r, 0f), 2L to button(Btn.X).center)
        assertEquals(Btn.DPAD_RIGHT or Btn.X, state)
    }

    @Test
    fun fingerFromDpadDoesNotPressButtonsItPassesOver() {
        val t = TouchTracker(layout)
        t.update(mapOf(1L to c + Offset(0.5f * r, 0f)))
        // the same finger is now exactly on the X button, but it still only steers the D-pad (to the right)
        assertEquals(Btn.DPAD_RIGHT, t.update(mapOf(1L to button(Btn.X).center)).buttons)
    }

    @Test
    fun liftingAllFingersReleasesEverything() {
        val t = TouchTracker(layout)
        t.update(mapOf(1L to c + Offset(0.6f * r, 0f), 2L to button(Btn.A).center))
        assertEquals(PadState.NEUTRAL, t.update(emptyMap()))
    }

    @Test
    fun startNeedsAnExactPressNotAnOverlap() {
        val start = button(Btn.START)
        assertEquals(0, buttons(1L to start.center + Offset(start.radius * 1.2f, 0f)))
    }
}
