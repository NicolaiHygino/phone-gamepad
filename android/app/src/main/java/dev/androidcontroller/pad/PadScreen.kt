package dev.androidcontroller.pad

import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PadScreen"
private val Background = Color(0xFF0E1116)
private val DpadIdle = Color(0x40FFFFFF)
private val DpadActive = Color(0xFFF2F2F2)

/** Buttons that give a haptic tick when newly pressed (everything except the D-pad). */
private const val TICK_MASK = Btn.A or Btn.B or Btn.X or Btn.Y or Btn.LB or Btn.RB or Btn.START

@Composable
fun PadScreen(settings: Settings, onSave: (Settings) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var pad by remember { mutableStateOf(PadState.NEUTRAL) }
    var latched by remember { mutableStateOf(false) } // SHOOT is held by a double tap
    var status by remember { mutableStateOf(PadClient.status()) }
    var showSettings by remember { mutableStateOf(settings.host.isBlank()) }
    // Previewed live while the sliders move; reverts to the saved layout if the dialog is cancelled.
    var layoutPrefs by remember(settings) { mutableStateOf(settings.layout) }
    val view = LocalView.current
    val measurer = rememberTextMeasurer(cacheSize = 16)

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            val latest = PadClient.status()
            if (latest.link != status.link) {
                Log.d(TAG, "link=${latest.link} rtt=%.1fms %s".format(latest.rttMs, latest.detail))
            }
            status = latest
            if (++tick % 12 == 0) { // every ~5 s, for measuring Wi-Fi quality from adb logcat
                PadClient.rttStats()?.let {
                    Log.d(TAG, "rtt median=%.2f p99=%.2f max=%.2f n=%d".format(it.median, it.p99, it.max, it.count))
                }
            }
            delay(400)
        }
    }

    Box(Modifier.fillMaxSize().background(Background)) {
        val layout = remember(size, layoutPrefs) {
            PadLayout(size.width.toFloat(), size.height.toFloat(), layoutPrefs)
        }
        val tracker = remember(layout) { TouchTracker(layout) }

        // A new tracker (the layout changed) or a stop (app in the background) has no fingers and no
        // latch, so show and send exactly that instead of a stale held SHOOT.
        fun releaseAll() {
            tracker.reset()
            latched = false
            pad = PadState.NEUTRAL
            PadClient.update(PadState.NEUTRAL)
        }
        LaunchedEffect(tracker) { releaseAll() }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { releaseAll() }

        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    size = it
                    Log.d(TAG, "canvas ${it.width}x${it.height}")
                }
                .systemGestureExclusion() // dragging the D-pad to the edge must not trigger "back"
                .pointerInput(tracker) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = HashMap<Long, Offset>()
                            for (c in event.changes) if (c.pressed) down[c.id.value] = c.position
                            val next = tracker.update(down)
                            val latchChanged = tracker.latched != latched
                            if (latchChanged) latched = tracker.latched
                            if (next != pad) {
                                if (latchChanged) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } else if (next.buttons and pad.buttons.inv() and TICK_MASK != 0) {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                }
                                Log.d(TAG, "buttons=0x%04x latched=%b fingers=%s".format(next.buttons, latched, down.values))
                                pad = next
                                PadClient.update(next)
                            } else if (latchChanged) {
                                // releasing the latch under a finger that is still down changes no button
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                    }
                },
        ) {
            drawDpad(layout, pad.buttons)
            for (b in layout.buttons) {
                drawButton(b, pad.buttons and b.bit != 0, latched && b.bit == TouchTracker.LATCH_BIT, measurer)
            }
        }

        StatusLine(settings, status) { showSettings = true }
    }

    if (showSettings) {
        SettingsDialog(
            initial = settings,
            layoutPrefs = layoutPrefs,
            onLayoutChange = { layoutPrefs = it },
            canDismiss = settings.host.isNotBlank(),
            onDismiss = {
                layoutPrefs = settings.layout
                showSettings = false
            },
            onSave = {
                showSettings = false
                onSave(it)
            },
        )
    }
}

private fun DrawScope.drawDpad(layout: PadLayout, bits: Int) {
    val c = layout.dpadCenter
    val r = layout.dpadRadius
    drawCircle(Color(0x14FFFFFF), radius = r, center = c)
    val w = r * 0.19f
    val inner = r * 0.12f
    val outer = r * 0.92f
    val len = outer - inner

    fun arm(bit: Int, topLeft: Offset, size: Size) = drawRoundRect(
        color = if (bits and bit != 0) DpadActive else DpadIdle,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(w * 0.5f),
    )
    arm(Btn.DPAD_UP, Offset(c.x - w, c.y - outer), Size(2 * w, len))
    arm(Btn.DPAD_DOWN, Offset(c.x - w, c.y + inner), Size(2 * w, len))
    arm(Btn.DPAD_LEFT, Offset(c.x - outer, c.y - w), Size(len, 2 * w))
    arm(Btn.DPAD_RIGHT, Offset(c.x + inner, c.y - w), Size(len, 2 * w))
}

private fun DrawScope.drawButton(b: PadButton, pressed: Boolean, held: Boolean, measurer: TextMeasurer) {
    drawCircle(b.color.copy(alpha = if (pressed) 0.95f else 0.18f), radius = b.radius, center = b.center)
    drawCircle(b.color, radius = b.radius, center = b.center, style = Stroke(width = b.radius * 0.07f))
    if (held) { // a white ring says "held down by the double tap", not "a finger is on it"
        drawCircle(Color.White, radius = b.radius * 1.14f, center = b.center, style = Stroke(width = b.radius * 0.07f))
    }

    val textColor = if (pressed) Color.Black else Color.White
    val caption = measurer.measure(
        b.caption,
        TextStyle(color = textColor, fontSize = (b.radius * 0.42f).toSp(), fontWeight = FontWeight.Bold),
    )
    val label = measurer.measure(
        if (held) "HELD" else b.label,
        TextStyle(color = textColor.copy(alpha = 0.75f), fontSize = (b.radius * 0.30f).toSp()),
    )
    val gap = b.radius * 0.05f
    val top = b.center.y - (caption.size.height + gap + label.size.height) / 2f
    drawText(caption, topLeft = Offset(b.center.x - caption.size.width / 2f, top))
    drawText(label, topLeft = Offset(b.center.x - label.size.width / 2f, top + caption.size.height + gap))
}

@Composable
private fun BoxScope.StatusLine(settings: Settings, status: PadClient.Status, onClick: () -> Unit) {
    val player = "P${settings.slot + 1}"
    val (text, color) = when (status.link) {
        PadClient.Link.OK ->
            "$player · connected · ${"%.1f".format(status.rttMs)} ms (worst ${"%.0f".format(status.maxMs)})" to
                Color(0xFF66BB6A)
        PadClient.Link.BUSY -> "$player · slot taken by another phone" to Color(0xFFFFB74D)
        PadClient.Link.BAD_KEY -> "$player · the PC wants a key: tap to set it" to Color(0xFFFFB74D)
        PadClient.Link.NO_REPLY -> "no reply from ${settings.host}:${settings.port}" to Color(0xFFEF5350)
        PadClient.Link.ERROR -> status.detail to Color(0xFFEF5350)
        PadClient.Link.OFF -> "tap to set the PC address" to Color(0xFFFFB74D)
    }
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsDialog(
    initial: Settings,
    layoutPrefs: LayoutPrefs,
    onLayoutChange: (LayoutPrefs) -> Unit,
    canDismiss: Boolean,
    onDismiss: () -> Unit,
    onSave: (Settings) -> Unit,
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var slot by remember { mutableIntStateOf(initial.slot) }
    var key by remember { mutableStateOf(initial.key) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }

    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Discovery.Found>?>(null) } // null = not searched yet
    val scope = rememberCoroutineScope()

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { Discovery.scan(portNumber ?: initial.port) }
            results = found
            scanning = false
            if (found.size == 1 && host.isBlank()) { // first run: one PC answered, so fill it in
                host = found[0].host
                port = found[0].port.toString()
            }
        }
    }
    LaunchedEffect(Unit) { if (initial.host.isBlank()) scan() }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { scan() }, enabled = !scanning) {
                        Text(if (scanning) "Searching…" else "Find PC")
                    }
                    if (results?.isEmpty() == true && !scanning) {
                        Text("None found. Is the server running and UDP ${portNumber ?: ""} open?", fontSize = 12.sp)
                    }
                }
                results?.forEach { found ->
                    TextButton(onClick = {
                        host = found.host
                        port = found.port.toString()
                    }) { Text("${found.name} · ${found.host}") }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("PC address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key (only if the PC server uses one)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = slot == 0, onClick = { slot = 0 }, label = { Text("Player 1") })
                    FilterChip(selected = slot == 1, onClick = { slot = 1 }, label = { Text("Player 2") })
                }

                Text("Button size", fontSize = 13.sp)
                Slider(
                    value = layoutPrefs.buttonScale,
                    onValueChange = { onLayoutChange(layoutPrefs.copy(buttonScale = it)) },
                    valueRange = LayoutPrefs.BUTTON_RANGE,
                )
                Text("D-pad size", fontSize = 13.sp)
                Slider(
                    value = layoutPrefs.dpadScale,
                    onValueChange = { onLayoutChange(layoutPrefs.copy(dpadScale = it)) },
                    valueRange = LayoutPrefs.DPAD_RANGE,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(
                        checked = layoutPrefs.mirrored,
                        onCheckedChange = { onLayoutChange(layoutPrefs.copy(mirrored = it)) },
                    )
                    Text("Swap hands (D-pad on the right)", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && portNumber != null,
                onClick = { onSave(Settings(host, portNumber!!, slot, key.trim(), layoutPrefs)) },
            ) { Text("Save") }
        },
        dismissButton = if (canDismiss) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else {
            null
        },
    )
}
