package dev.androidcontroller.pad

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    // Phone landscape shapes from 16:9 to 20:9.
    private val screens = listOf(1280f to 720f, 1600f to 720f, 1920f to 1080f, 2408f to 1080f)
    private val prefs = listOf(
        LayoutPrefs(0.8f, 0.8f, false),
        LayoutPrefs(1f, 1f, false),
        LayoutPrefs(1.2f, 1.2f, false),
        LayoutPrefs(1.2f, 0.8f, true),
        LayoutPrefs(0.8f, 1.2f, true),
        LayoutPrefs(1.2f, 1.2f, true),
    )

    private fun forEveryLayout(check: (PadLayout, String) -> Unit) {
        for ((w, h) in screens) for (p in prefs) check(PadLayout(w, h, p), "${w.toInt()}x${h.toInt()} $p")
    }

    private fun button(l: PadLayout, bit: Int) = l.buttons.first { it.bit == bit }

    @Test
    fun everyControlStaysOnScreen() = forEveryLayout { l, name ->
        for (b in l.buttons) {
            assertTrue(
                "$name ${b.caption} off screen",
                b.center.x - b.radius >= 0f && b.center.x + b.radius <= l.width &&
                    b.center.y - b.radius >= 0f && b.center.y + b.radius <= l.height,
            )
        }
        val r = l.dpadRadius
        assertTrue(
            "$name D-pad off screen",
            l.dpadCenter.x - r >= 0f && l.dpadCenter.x + r <= l.width &&
                l.dpadCenter.y - r >= 0f && l.dpadCenter.y + r <= l.height,
        )
    }

    @Test
    fun controlsNeverOverlap() = forEveryLayout { l, name ->
        for (i in l.buttons.indices) for (j in i + 1 until l.buttons.size) {
            val a = l.buttons[i]
            val b = l.buttons[j]
            assertTrue(
                "$name ${a.caption} overlaps ${b.caption}",
                (a.center - b.center).getDistance() >= a.radius + b.radius,
            )
        }
        for (b in l.buttons) {
            assertTrue(
                "$name D-pad overlaps ${b.caption}",
                (l.dpadCenter - b.center).getDistance() >= l.dpadRadius * 0.92f + b.radius,
            )
        }
    }

    @Test
    fun everyButtonIsPressedAtItsCentreAtAnySize() = forEveryLayout { l, name ->
        for (b in l.buttons) {
            assertEquals("$name ${b.caption}", b.bit, TouchTracker(l).update(mapOf(1L to b.center)).buttons)
        }
    }

    @Test
    fun dpadStillSteersAtAnySize() = forEveryLayout { l, name ->
        val right = l.dpadCenter + Offset(0.6f * l.dpadRadius, 0f)
        assertEquals(name, Btn.DPAD_RIGHT, TouchTracker(l).update(mapOf(1L to right)).buttons)
    }

    @Test
    fun mirroringSwapsHandsAndNothingElse() {
        val normal = PadLayout(1600f, 720f)
        val mirrored = PadLayout(1600f, 720f, LayoutPrefs(mirrored = true))
        assertTrue(normal.dpadCenter.x < 800f && mirrored.dpadCenter.x > 800f)
        val a = button(normal, Btn.A)
        val am = button(mirrored, Btn.A)
        assertTrue(a.center.x > 800f && am.center.x < 800f)
        assertEquals(1600f - a.center.x, am.center.x, 0.01f)
        assertEquals(a.center.y, am.center.y, 0.01f)
        assertEquals(a.radius, am.radius, 0.001f)
        assertEquals(800f, button(mirrored, Btn.START).center.x, 0.01f) // pause stays in the middle
    }

    @Test
    fun sizesScaleAndOutOfRangeValuesAreClamped() {
        val small = PadLayout(1600f, 720f, LayoutPrefs(buttonScale = 0.8f, dpadScale = 0.8f))
        val big = PadLayout(1600f, 720f, LayoutPrefs(buttonScale = 1.2f, dpadScale = 1.2f))
        assertEquals(1.5f, button(big, Btn.A).radius / button(small, Btn.A).radius, 0.001f)
        assertEquals(1.5f, big.dpadRadius / small.dpadRadius, 0.001f)

        val absurd = PadLayout(1600f, 720f, LayoutPrefs(buttonScale = 9f, dpadScale = 9f))
        assertEquals(big.dpadRadius, absurd.dpadRadius, 0.001f)
        assertEquals(button(big, Btn.A).radius, button(absurd, Btn.A).radius, 0.001f)
    }

    @Test
    fun defaultLayoutIsUnchangedByTheNewOptions() {
        val l = PadLayout(1600f, 720f)
        assertEquals(Offset(0.36f * 720f, 0.62f * 720f), l.dpadCenter)
        assertEquals(Offset(1600f - 0.36f * 720f, 0.62f * 720f + 0.19f * 720f), button(l, Btn.A).center)
    }
}
