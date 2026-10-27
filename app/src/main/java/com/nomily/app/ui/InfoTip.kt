package com.nomily.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A clickable `?` icon that, when clicked, displays a brief description at the bottom.
 *
 * Used for **single‑line** hints: such a hint should not occupy an entire section footer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoTip(text: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }, modifier = modifier.size(28.dp)) {
        Icon(
            Icons.Outlined.HelpOutline,
            contentDescription = text,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
    if (show) {
        ModalBottomSheet(onDismissRequest = { show = false }) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
}
