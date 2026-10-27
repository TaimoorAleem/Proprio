package dev.proprio.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.proprio.mobile.LivePreviewController
import dev.proprio.mobile.domain.AxisRange
import dev.proprio.mobile.domain.Level
import dev.proprio.mobile.domain.LinkStatus
import dev.proprio.mobile.domain.LiveHealthUi
import dev.proprio.mobile.domain.LiveSeries
import kotlin.math.ceil

/** Series colors for x / y / z (Okabe-Ito based; separate sets so each has contrast on its own background). */
internal object LivePalette {
    val light = listOf(Color(0xFFD55E00), Color(0xFF0072B2), Color(0xFF009E73))
    val dark = listOf(Color(0xFFE69F00), Color(0xFF56B4E9), Color(0xFFCC79A7))
}

private val AXES = listOf("x", "y", "z")

/**
 * "Live" card on the active session screen: health row plus two stacked charts. Shows the watch's incoming data
 * for display only; nothing here is recorded or uploaded. The lease is held only while this composable is in the
 * started state (screen on, app in the foreground).
 *
 * @param tickMs redraw cadence (40 ms = 25 fps); 0 disables the ticker (used by static UI tests).
 */
@Composable
fun LivePanel(
    controller: LivePreviewController,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    tickMs: Long = 40,
) {
    val model = controller.model
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(controller, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.runVisible() }
    }

    val frame = remember { mutableLongStateOf(0L) }
    var health by remember { mutableStateOf(model.healthUi(controller.now())) }
    LaunchedEffect(controller, tickMs) {
        var n = 0L
        while (true) {
            val now = controller.now()
            model.updateRanges(now)
            frame.longValue = now
            if (n % HEALTH_EVERY == 0L || tickMs <= 0) health = model.healthUi(now)
            if (tickMs <= 0) break
            n++
            kotlinx.coroutines.delay(tickMs)
        }
    }

    val dark = isSystemInDarkTheme()
    val colors = if (dark) LivePalette.dark else LivePalette.light
    Column(modifier.fillMaxWidth().testTag("live_panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live (display only, not recorded)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onToggleExpand, modifier = Modifier.testTag("live_expand")) {
                Text(if (expanded) "Close" else "Expand")
            }
        }
        HealthRow(health)
        Legend(colors)
        val chartMod = if (expanded) Modifier.weight(1f) else Modifier.height(150.dp)
        SensorChart(
            "Acceleration (g)", model.accRange, model.acc, colors, model.windowS, frame, controller::now,
            { model.windowEnd(it) }, chartMod.testTag("live_chart_acc"),
            "%.1f".format(model.accRange.hi), "%.1f".format(model.accRange.mid()), "%.1f".format(model.accRange.lo),
        )
        SensorChart(
            "Angular rate (deg/s)", model.gyrRange, model.gyr, colors, model.windowS, frame, controller::now,
            { model.windowEnd(it) }, chartMod.testTag("live_chart_gyr"),
            "%.0f".format(model.gyrRange.hi), "%.0f".format(model.gyrRange.mid()), "%.0f".format(model.gyrRange.lo),
        )
    }
}

private const val HEALTH_EVERY = 6L // about 4 Hz at 40 ms ticks

/** Inline card variant plus the full-screen dialog; only one of them is composed at a time (one lease holder). */
@Composable
fun LiveSection(controller: LivePreviewController, modifier: Modifier = Modifier, tickMs: Long = 40) {
    var expanded by remember { mutableStateOf(false) }
    if (expanded) {
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                LivePanel(controller, expanded = true, onToggleExpand = { expanded = false }, modifier = Modifier.padding(12.dp).fillMaxHeight(), tickMs = tickMs)
            }
        }
    } else {
        Card(modifier.fillMaxWidth()) {
            LivePanel(controller, expanded = false, onToggleExpand = { expanded = true }, modifier = Modifier.padding(12.dp), tickMs = tickMs)
        }
    }
}

// ---- health row ------------------------------------------------------------------------------------

@Composable
internal fun HealthRow(h: LiveHealthUi, modifier: Modifier = Modifier) {
    val link = when (h.link) {
        LinkStatus.LIVE -> Chip("Link", "LIVE", Level.OK)
        LinkStatus.STALE -> Chip("Link", "STALE %.1f s".format(h.linkAgeS ?: 0.0), Level.WARN)
        LinkStatus.NO_DATA -> Chip("Link", "NO DATA", Level.BAD)
    }
    fun rate(name: String, hz: Float?, level: Level) =
        Chip(name, if (hz == null || hz <= 0f) "- Hz" else "%.1f Hz %s".format(hz, if (level == Level.OK) "OK" else "CHECK"), level)
    val chips = listOf(
        "live_health_link" to link,
        "live_health_segment" to Chip("Segment", h.segment, Level.NEUTRAL),
        "live_health_acc" to rate("ACC", h.accHz, h.accLevel),
        "live_health_gyr" to rate("GYR", h.gyrHz, h.gyrLevel),
        "live_health_gaps" to Chip("Gaps >100 ms", "${h.gaps}${if (h.gaps == 0) " OK" else " BAD"}", h.gapsLevel),
        "live_health_sat" to Chip("Saturation", "${h.saturated}${if (h.saturated == 0) " OK" else " CHECK"}", h.saturatedLevel),
        "live_health_recorder" to Chip(
            "Watch recorder",
            when {
                h.writeError -> "WRITE ERROR"
                h.recorderDropped > 0 -> "${h.recorderDropped} dropped"
                else -> "OK"
            },
            h.recorderLevel,
        ),
        "live_health_preview" to Chip(
            "Preview lost", "${h.previewDropped} (display only)", if (h.previewDropped == 0L) Level.OK else Level.NEUTRAL,
        ),
    )
    Column(modifier.fillMaxWidth().testTag("live_health"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (tag, chip) -> ChipView(chip, Modifier.weight(1f).testTag(tag)) }
            }
        }
        Text(
            "Written: acc ${h.accWritten}, gyr ${h.gyrWritten}" + (h.strategy?.let { "  |  anti-freeze: $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("live_health_counts"),
        )
    }
}

internal data class Chip(val label: String, val value: String, val level: Level)

@Composable
private fun ChipView(chip: Chip, modifier: Modifier) {
    val dark = isSystemInDarkTheme()
    val tint = when (chip.level) {
        Level.OK -> if (dark) Color(0xFF7FD99A) else Color(0xFF1B6E34)
        Level.WARN -> if (dark) Color(0xFFFFC857) else Color(0xFF8A5A00)
        Level.BAD -> MaterialTheme.colorScheme.error
        Level.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val glyph = when (chip.level) {
        Level.OK -> "✓"
        Level.WARN -> "!"
        Level.BAD -> "✕"
        Level.NEUTRAL -> "•"
    }
    Row(
        modifier.background(tint.copy(alpha = 0.10f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(glyph, color = tint, style = MaterialTheme.typography.labelLarge)
        Column {
            Text(chip.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(chip.value, style = MaterialTheme.typography.labelLarge, color = tint)
        }
    }
}

@Composable
private fun Legend(colors: List<Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AXES.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(width = 16.dp, height = 3.dp).background(colors[i]))
                Text(a, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- chart ------------------------------------------------------------------------------------------

@Composable
internal fun SensorChart(
    title: String,
    range: AxisRange,
    series: LiveSeries,
    colors: List<Color>,
    windowS: Double,
    frame: androidx.compose.runtime.State<Long>,
    now: () -> Long,
    windowEnd: (Long) -> Double,
    modifier: Modifier,
    labelHi: String,
    labelMid: String,
    labelLo: String,
) {
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val axisText = MaterialTheme.colorScheme.onSurfaceVariant
    val paths = remember { List(3) { Path() } }
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = axisText)
        Row(Modifier.weight(1f, fill = true).fillMaxWidth()) {
            // Axis values as text tokens; the range snaps to round numbers so these rarely change.
            Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text(labelHi, color = axisText, fontSize = 10.sp, textAlign = TextAlign.End)
                Text(labelMid, color = axisText, fontSize = 10.sp, textAlign = TextAlign.End)
                Text(labelLo, color = axisText, fontSize = 10.sp, textAlign = TextAlign.End)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp)) {
                frame.value // redraw on every tick (draw phase only, no recomposition)
                val w = size.width
                val h = size.height
                val lo = range.lo
                val span = (range.hi - range.lo).coerceAtLeast(1e-9)
                val end = windowEnd(now())
                val start = end - windowS
                // recessive grid: quarters horizontally, every 2 s vertically (they scroll with the data)
                for (i in 0..4) {
                    val y = h * i / 4f
                    drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                var gt = ceil(start / 2.0) * 2.0
                while (gt <= end) {
                    val x = ((gt - start) / windowS * w).toFloat()
                    drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    gt += 2.0
                }
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (a in 0..2) {
                    val p = paths[a]
                    p.reset()
                    var started = false
                    for (i in 0 until series.size) {
                        val t = series.t(i)
                        if (t < start) continue
                        val x = ((t - start) / windowS * w).toFloat()
                        val y = (h - ((series.value(a, i) - lo) / span * h)).toFloat().coerceIn(0f, h)
                        if (started) p.lineTo(x, y) else { p.moveTo(x, y); started = true }
                    }
                    if (started) drawPath(p, colors[a], style = stroke)
                }
            }
        }
    }
}
