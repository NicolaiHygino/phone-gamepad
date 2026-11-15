package dev.androidcontroller.pad

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- double tap on SHOOT holds it down ---

    private class Clock {
        var now = 1_000L
    }

    /** A tracker with a controllable clock and a one-finger helper for the SHOOT and JUMP buttons. */
    private inner class Pad {
        val clock = Clock()
        val tracker = TouchTracker(layout) { clock.now }
        val shoot = button(Btn.X).center
        val jump = button(Btn.A).center

        fun press(at: Offset): Int = tracker.update(mapOf(1L to at)).buttons

        fun lift(): Int = tracker.update(emptyMap()).buttons

        fun advance(ms: Long) {
            clock.now += ms
        }

        /** Press and release SHOOT, lasting [pressMs]; returns the state while it is down. */
        fun tap(pressMs: Long = 80): Int {
            val down = press(shoot)
            advance(pressMs)
            lift()
            return down
        }
    }

    @Test
    fun doubleTapOnShootHoldsItAfterTheFingerLifts() {
        val pad = Pad()
        pad.tap()
        pad.advance(100)
        assertEquals(Btn.X, pad.press(pad.shoot))
        pad.advance(80)
        assertEquals(Btn.X, pad.lift())
        assertTrue(pad.tracker.latched)
        pad.advance(5_000)
        assertEquals(Btn.X, pad.lift()) // still held much later
    }

    @Test
    fun theFirstTapOfADoubleTapIsAnOrdinaryShot() {
        val pad = Pad()
        assertEquals(Btn.X, pad.press(pad.shoot))
        pad.advance(80)
        assertEquals(0, pad.lift())
        assertFalse(pad.tracker.latched)
    }

    @Test
    fun aSingleTapNeverHolds() {
        val pad = Pad()
        pad.tap()
        pad.advance(2_000)
        assertEquals(0, pad.lift())
        assertFalse(pad.tracker.latched)
    }

    @Test
    fun aSlowSecondTapDoesNotHold() {
        val pad = Pad()
        pad.tap()
        pad.advance(TouchTracker.DOUBLE_TAP_MS + 1)
        pad.tap()
        assertFalse(pad.tracker.latched)
        assertEquals(0, pad.lift())
    }

    @Test
    fun holdingShootThenPressingAgainDoesNotHold() {
        val pad = Pad()
        pad.tap(pressMs = TouchTracker.TAP_MS + 1) // a hold, not a tap
        pad.advance(100)
        pad.tap()
        assertFalse(pad.tracker.latched)
        assertEquals(0, pad.lift())
    }

    @Test
    fun doubleTapAgainReleasesIt() {
        val pad = Pad()
        pad.tap(); pad.advance(100); pad.tap()
        assertTrue(pad.tracker.latched)
        pad.advance(3_000)
        pad.tap()
        pad.advance(100)
        // the unlocking press: the finger is on SHOOT, so it stays down until the finger lifts
        assertEquals(Btn.X, pad.press(pad.shoot))
        assertFalse(pad.tracker.latched)
        pad.advance(80)
        assertEquals(0, pad.lift())
    }

    @Test
    fun aThirdQuickTapDoesNotUndoTheLock() {
        val pad = Pad()
        pad.tap(); pad.advance(100); pad.tap()
        pad.advance(100)
        pad.tap()
        assertTrue(pad.tracker.latched)
        assertEquals(Btn.X, pad.lift())
    }

    @Test
    fun theHeldShootCombinesWithOtherButtons() {
        val pad = Pad()
        pad.tap(); pad.advance(100); pad.tap()
        assertEquals(Btn.X or Btn.A, pad.press(pad.jump)) // jump with the same thumb while shooting
        assertEquals(Btn.X, pad.lift())
    }

    @Test
    fun otherButtonsAreNeverHeld() {
        val pad = Pad()
        for (i in 0..1) {
            pad.press(pad.jump)
            pad.advance(80)
            pad.lift()
            pad.advance(100)
        }
        assertFalse(pad.tracker.latched)
        assertEquals(0, pad.lift())
    }

    @Test
    fun resetReleasesTheHold() {
        val pad = Pad()
        pad.tap(); pad.advance(100); pad.tap()
        pad.tracker.reset()
        assertFalse(pad.tracker.latched)
        assertEquals(0, pad.lift())
    }

    @Test
    fun resetForgetsAHalfFinishedDoubleTap() {
        val pad = Pad()
        pad.tap()
        pad.tracker.reset()
        pad.advance(100)
        pad.tap()
        assertFalse(pad.tracker.latched)
    }
}
