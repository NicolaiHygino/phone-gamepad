package dev.androidcontroller.pad

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/** What the user can adjust about the pad's look. */
data class LayoutPrefs(
    /** Face and shoulder buttons, relative to the default size. */
    val buttonScale: Float = 1f,
    /** The D-pad, relative to the default size. */
    val dpadScale: Float = 1f,
    /** Swap hands: D-pad on the right, buttons on the left. */
    val mirrored: Boolean = false,
) {
    companion object {
        // The limits keep every control on screen and clear of its neighbours on phone screens.
        val BUTTON_RANGE = 0.8f..1.2f
        val DPAD_RANGE = 0.8f..1.2f
    }
}

/** A round touch button. [caption] is the in-game action, [label] the Xbox button name. */
data class PadButton(
    val bit: Int,
    val caption: String,
    val label: String,
    val center: Offset,
    val radius: Float,
    val color: Color,
    /** Touch area relative to the drawn radius. >1 makes neighbours overlap, so one thumb can press two. */
    val hitScale: Float = 1.35f,
) {
    fun hits(p: Offset): Boolean = (p - center).getDistance() <= radius * hitScale
}

/** Pure geometry for a landscape screen. Everything is derived from the screen height. */
class PadLayout(val width: Float, val height: Float, prefs: LayoutPrefs = LayoutPrefs()) {
    private val u = height

    val dpadRadius = 0.30f * u * prefs.dpadScale.coerceIn(LayoutPrefs.DPAD_RANGE)
    val dpadCenter: Offset

    val buttons: List<PadButton>

    init {
        val scale = prefs.buttonScale.coerceIn(LayoutPrefs.BUTTON_RANGE)
        val margin = 0.03f * u
        fun mx(x: Float) = if (prefs.mirrored) width - x else x // swap hands

        // D-pad bottom-left; the clamps keep it fully on screen at any size.
        dpadCenter = Offset(
            mx(maxOf(0.36f * u, dpadRadius + margin)),
            minOf(0.62f * u, height - dpadRadius - margin),
        )

        // Xbox diamond bottom-right: A bottom, B right, X left, Y top.
        val d = 0.19f * u * scale
        val r = 0.105f * u * scale
        val extent = d + r
        val face = Offset(
            mx(width - maxOf(0.36f * u, extent + margin)),
            minOf(0.62f * u, height - extent - margin),
        )

        val shoulder = 0.09f * u * scale
        val shoulderY = maxOf(0.15f * u, shoulder + 0.04f * u)
        val gray = Color(0xFF9AA4B2)
        // Captions are Cuphead's default Xbox bindings.
        buttons = listOf(
            PadButton(Btn.A, "JUMP", "A", face + Offset(0f, d), r, Color(0xFF4CAF50)),
            PadButton(Btn.B, "EX", "B", face + Offset(d, 0f), r, Color(0xFFE53935)),
            PadButton(Btn.X, "SHOOT", "X", face + Offset(-d, 0f), r, Color(0xFF1E88E5)),
            PadButton(Btn.Y, "DASH", "Y", face + Offset(0f, -d), r, Color(0xFFFDD835)),
            PadButton(Btn.LB, "SWAP", "LB", Offset(mx(0.42f * u), shoulderY), shoulder, gray),
            PadButton(Btn.RB, "LOCK", "RB", Offset(mx(width - 0.42f * u), shoulderY), shoulder, gray),
            PadButton(
                Btn.START, "PAUSE", "START",
                Offset(width / 2f, minOf(0.80f * u, height - 0.065f * u - margin)),
                0.065f * u, gray, hitScale = 1f,
            ),
        )
    }

    fun inDpadZone(p: Offset): Boolean = (p - dpadCenter).getDistance() <= dpadRadius * DPAD_CAPTURE

    /** 8-way direction for a finger at [p], measured from the D-pad centre. */
    fun dpadBits(p: Offset): Int {
        val d = p - dpadCenter
        val dist = d.getDistance()
        if (dist < DPAD_DEADZONE * dpadRadius) return 0
        val nx = d.x / dist
        val ny = d.y / dist
        var bits = 0
        if (nx > DPAD_AXIS_THRESHOLD) bits = bits or Btn.DPAD_RIGHT
        else if (nx < -DPAD_AXIS_THRESHOLD) bits = bits or Btn.DPAD_LEFT
        if (ny > DPAD_AXIS_THRESHOLD) bits = bits or Btn.DPAD_DOWN
        else if (ny < -DPAD_AXIS_THRESHOLD) bits = bits or Btn.DPAD_UP
        return bits
    }

    private companion object {
        const val DPAD_CAPTURE = 1.2f // a new finger this close to the ring grabs the D-pad
        const val DPAD_DEADZONE = 0.15f // fraction of the radius with no direction
        const val DPAD_AXIS_THRESHOLD = 0.38f // sin(22.5 deg): gives 8 equal 45-degree sectors
    }
}

/**
 * Turns the set of fingers currently on the screen into a [PadState].
 * One finger owns the D-pad and keeps it even when it slides outside the ring, so dragging
 * to the edge never triggers other buttons. All other fingers press whatever they touch.
 * A finger that lands on a button is always a button finger: buttons win over the D-pad's
 * capture zone, so a large D-pad can never swallow a nearby button.
 *
 * SHOOT can be held down hands-free: a double tap latches it on, and the next double tap lets go.
 * That frees the thumb for JUMP and DASH, which is the hard part of shooting while jumping on a screen.
 * The first tap must be short, so holding SHOOT, lifting and pressing again never latches by accident,
 * and the press that toggles the latch cannot also start another double tap.
 * [clockMs] is only read for that timing and must be monotonic.
 */
class TouchTracker(
    private val layout: PadLayout,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var dpadPointer = NONE

    /** True while SHOOT is held down by the latch rather than by a finger. */
    var latched = false
        private set

    private var shootDown = false
    private var shootDownAt = 0L
    private var lastTapEnd = NEVER // when the last short, unconsumed tap on SHOOT ended
    private var toggledByThisPress = false

    /** Forgets every finger and the latch: everything is released. */
    fun reset() {
        dpadPointer = NONE
        latched = false
        shootDown = false
        lastTapEnd = NEVER
        toggledByThisPress = false
    }

    /** [pointers] maps pointer id -> position for every finger currently down. */
    fun update(pointers: Map<Long, Offset>): PadState {
        if (dpadPointer !in pointers) dpadPointer = NONE
        if (dpadPointer == NONE) {
            dpadPointer = pointers.entries.firstOrNull { (_, p) ->
                layout.inDpadZone(p) && layout.buttons.none { it.hits(p) }
            }?.key ?: NONE
        }
        var buttons = 0
        for ((id, p) in pointers) {
            if (id == dpadPointer) {
                buttons = buttons or layout.dpadBits(p)
            } else {
                for (b in layout.buttons) if (b.hits(p)) buttons = buttons or b.bit
            }
        }
        trackShootTaps(touched = buttons and LATCH_BIT != 0)
        return PadState(buttons = if (latched) buttons or LATCH_BIT else buttons)
    }

    private fun trackShootTaps(touched: Boolean) {
        if (touched == shootDown) return
        shootDown = touched
        val now = clockMs()
        if (touched) {
            shootDownAt = now
            toggledByThisPress = lastTapEnd != NEVER && now - lastTapEnd <= DOUBLE_TAP_MS
            if (toggledByThisPress) latched = !latched
            lastTapEnd = NEVER
        } else if (!toggledByThisPress && now - shootDownAt <= TAP_MS) {
            lastTapEnd = now
        }
    }

    companion object {
        /** The button that can be latched: SHOOT. */
        const val LATCH_BIT = Btn.X

        /** A press that lasts longer than this is a hold, not a tap. */
        const val TAP_MS = 250L

        /** Longest gap between lifting after a tap and the press that completes the double tap. */
        const val DOUBLE_TAP_MS = 300L

        private const val NONE = Long.MIN_VALUE
        private const val NEVER = Long.MIN_VALUE
    }
}
