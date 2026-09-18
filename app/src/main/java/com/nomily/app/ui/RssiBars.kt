package com.nomily.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Four‑segment Wi‑Fi style signal bars, tiered:
 *
 * ```
 *   −30..−50 → 4 bars (excellent)
 *   −51..−65 → 3 bars (good)
 *   −66..−75 → 2 bars (fair)
 *   −76..−85 → 1 bar (weak)
 *   < −85    → 0 bars (unavailable)
 * ```
 */
@Composable
fun RssiBars(rssi: Int, modifier: Modifier = Modifier) {
    val bars = barsFor(rssi)
    Row(
        modifier.semantics { contentDescription = "Signal strength: $bars of 4" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(4) { i ->
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (i < bars) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

internal fun barsFor(rssi: Int): Int = when {
    rssi <= -86 -> 0
    rssi <= -76 -> 1
    rssi <= -66 -> 2
    rssi <= -51 -> 3
    else -> 4
}
