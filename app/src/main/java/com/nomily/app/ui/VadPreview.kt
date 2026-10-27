package com.nomily.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nomily.app.NomiViewModel.LocalClip
import com.nomily.app.R
import com.nomily.app.audio.LocalVad
import com.nomily.app.audio.PcmCodec
import com.nomily.app.core.audio.VadMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * VAD debug preview.
 *
 * Run [LocalVad.analyze] once, plot the RMS envelope, highlight speech intervals in green,
 * and draw lines for the threshold and noise floor.
 * Adjust the multiplier and hysteresis interactively to evaluate whether this heuristic
 * accurately segments speech in real recordings.
 * **Consider exposing these two controls in the official settings only after tuning is complete.**
 *
 * Only visible in developer mode (tap the version number 7 times).
 */
@Composable
fun VadPreviewDialog(clip: LocalClip, onDismiss: () -> Unit) {
    var multiplier by remember { mutableStateOf(VadMath.DEFAULT_THRESHOLD_MULTIPLIER.toFloat()) }
    var hangover by remember { mutableStateOf(VadMath.DEFAULT_HANGOVER_SEC.toFloat()) }
    var report by remember { mutableStateOf<VadMath.VadReport?>(null) }
    var analyzing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Dragging the slider triggers continuous recalculation, which is expensive to decode once — wait 300ms before recalculating (debounce)
    LaunchedEffect(multiplier, hangover) {
        delay(300)
        analyzing = true
        error = null
        val audio = clip.audio
        if (audio == null) {
            analyzing = false
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.Default) {
                // Decoding is a **blocking** call; coroutine cancellation won't stop it automatically — pass `isActive` in,
                // otherwise each slider drag spawns another full decode (on multi-hour recordings, this can overheat the device and freeze the UI).
                LocalVad.analyze(audio, multiplier.toDouble(), hangover.toDouble()) { !isActive }
            }
        }.onSuccess {
            report = it
            analyzing = false
        }.onFailure {
            // Swapped out by the next round of analysis; not an error: no red text displayed, no spinner closed (the one that took over is still running)
            if (it !is PcmCodec.Cancelled && it !is kotlinx.coroutines.CancellationException) {
                error = it.message ?: it.javaClass.simpleName
                analyzing = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Card style: top leaves space for the status bar exposing the parent view, with rounded top corners
        Surface(
            Modifier.fillMaxSize().statusBarsPadding(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("VAD Preview", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(clip.name, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)

                    //  Must provide visual feedback during recalculation: when `report != null`, if only the report is drawn,
                    //  there is **no feedback at all** on the UI for tens of seconds after dragging the slider
                    //  (the old waveform remains visible, making it unclear that recalculation is in progress).
                    //  Therefore, the spinner is controlled separately based on `analyzing`, while the report remains displayed below.
                    if (analyzing && report != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("  Analyzing…", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    when {
                        report != null -> ReportBody(report!!)
                        analyzing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            // ⚠️ size must not only specify height: when only the height is constrained, the circle is still drawn with the default
                            // 40dp width, which exceeds this line's bounds and overlaps the "Tuning" text below
                            // (confirmed on a real device, 2026-08-12). When providing square dimensions, also reduce the stroke width;
                            // otherwise, a 20dp circle with the default 4dp stroke looks like a blob.
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("  Analyzing…", style = MaterialTheme.typography.bodySmall)
                        }
                        error != null -> Text(
                            error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // ── Tuning ──
                    Text("Tuning", style = MaterialTheme.typography.titleSmall)
                    SliderRow("Threshold × noise floor", "%.2f".format(multiplier), multiplier, 1f..10f) {
                        multiplier = it
                    }
                    SliderRow("Hangover", "%.2f s".format(hangover), hangover, 0f..1f) { hangover = it }
                    OutlinedButton(onClick = {
                        multiplier = VadMath.DEFAULT_THRESHOLD_MULTIPLIER.toFloat()
                        hangover = VadMath.DEFAULT_HANGOVER_SEC.toFloat()
                    }) { Text("Reset to defaults") }
                }
            }
        }
    }
}

@Composable
private fun ReportBody(r: VadMath.VadReport) {
    val savedPct = if (r.totalDuration > 0) r.savedDuration / r.totalDuration * 100 else 0.0
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat("Total", "%.1fs".format(r.totalDuration))
        Stat("Speech", "%.1fs".format(r.speechDuration))
        Stat("Saved", "%.1fs (%.0f%%)".format(r.savedDuration, savedPct))
    }
    VadWaveform(r, Modifier.fillMaxWidth().height(160.dp))
    Text(
        "Noise floor %.0f · Threshold %.0f (× %.2f) · %d regions"
            .format(r.noiseFloor, r.threshold, r.thresholdMultiplier, r.ranges.size),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

/** RMS envelope + speech interval background color + threshold/noise floor two lines. */
@Composable
private fun VadWaveform(r: VadMath.VadReport, modifier: Modifier = Modifier) {
    val speech = Color(0x4034C759)
    val bar = Color(0x99007AFF)
    val thresholdColor = Color.Red
    val floorColor = Color.Gray
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (r.rms.isEmpty() || r.totalDuration <= 0) return@Canvas
        val peak = (r.rms.maxOrNull() ?: 1.0).coerceAtLeast(1.0)

        // Lay the foundation for the voice interval first
        r.ranges.forEach { seg ->
            val x0 = (seg.start / r.totalDuration * w).toFloat()
            val x1 = (seg.end / r.totalDuration * w).toFloat()
            drawRect(
                color = speech,
                topLeft = androidx.compose.ui.geometry.Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), h),
            )
        }
        // RMS vertical bars: when the window has far more rows than pixels, take the maximum value per column (otherwise details would be lost due to sampling)
        val cols = w.toInt().coerceAtLeast(1)
        for (x in 0 until cols) {
            val from = (x.toDouble() / cols * r.rms.size).toInt()
            val to = ((x + 1).toDouble() / cols * r.rms.size).toInt().coerceAtMost(r.rms.size)
            if (to <= from) continue
            var m = 0.0
            for (i in from until to) m = maxOf(m, r.rms[i])
            val barH = (m / peak * h).toFloat()
            drawRect(
                color = bar,
                topLeft = androidx.compose.ui.geometry.Offset(x.toFloat(), h - barH),
                size = androidx.compose.ui.geometry.Size(1f, barH),
            )
        }
        fun line(v: Double, c: Color) {
            val y = (h - v / peak * h).toFloat()
            if (y in 0f..h) {
                drawLine(c, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1.5f)
            }
        }
        line(r.threshold, thresholdColor)
        line(r.noiseFloor, floorColor)
    }
}

@Composable
private fun SliderRow(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
