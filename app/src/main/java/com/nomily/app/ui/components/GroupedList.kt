package com.nomily.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * This component: gray background with a white card containing several equal-height rows,
 * separated by thin divider lines.
 *
 * Before this, `SettingsScreen`, `DeviceSheet`, and `FastTransferSheet` each had their own
 * implementation of `Group / RowDivider / InfoRow / ActionRow`. They looked nearly identical
 * but details had started to drift (e.g., whether the group header title is capitalized,
 * top margin of 18 vs. 24, whether the card needs extra padding). Fixing one instance to
 * align with others required touching multiple files and was prone to missing some copies.
 *
 * We standardized on the **`SettingsScreen` version**: it was measured screen-by-screen
 * against WDA on 2026-08-06, while the other two were written earlier based on visual
 * judgment.
 */
object NomiDimens {
    /**
     * The horizontal margin between the card and the screen edges, as well as the horizontal padding within each row for its own content.
     *
     * Both values are 16, which is not a coincidence: the card itself has no padding; padding is applied by the rows. If another layer is wrapped around the card,
     * the row's 16 would stack up to 30+, making the margins noticeably too wide.
     *
     * Source: Verified via WDA on 2026-08-06. These values are provided by the system and cannot be found in the source code.
     */
    val screenInset = 16.dp

    /** Inline horizontal padding, see the description of [screenInset]. */
    val rowInset = 16.dp

    /**
     * Minimum row height: 48dp, which is Android's minimum touch target size. The trade-off for accessibility is worthwhile.
     */
    val rowMinHeight = 48.dp

    /** Card corner radius. WDA measured 10pt. */
    val cardCorner = 10.dp

    /** Distance from the group header (gray subtitle) to the previous card. */
    val groupHeaderTop = 18.dp

    /** The distance from the group header to its own card. */
    val groupHeaderBottom = 6.dp

    /**
     * Left inset of the divider within the card: left inset 16dp, right end aligned with the card, not full-width.
     */
    val dividerStartInset = 16.dp

    /** Vertical padding for the card's outer footnote. */
    val footerTop = 2.dp
    val footerBottom = 8.dp

    /** Spacing between icons and text in the action row, as well as icon dimensions. */
    val actionIconSize = 22.dp
    val actionIconGap = 12.dp
}

/**
 * A group header + a white card (+ an optional footnote outside the card).
 *
 * The title is **all uppercase**: on real-device screenshots it reads
 * "AZURE SPEECH". Note that **the a11y label obtained via WDA is the original text** (not uppercased); judging by the label alone would
 * mistakenly conclude it's not uppercase — the screenshot is authoritative. Chinese group names are unaffected (uppercasing is a no-op).
 *
 * When the title is empty and there's no trailing, the whole row takes no space, leaving just a gap the same height as the group header.
 */
@Composable
fun Group(
    title: String? = null,
    /** Actions on the right side of the group header (e.g., "How to get the key" for Azure). */
    trailing: (@Composable () -> Unit)? = null,
    /** Gray footnote outside the card. */
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!title.isNullOrBlank() || trailing != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = NomiDimens.rowInset,
                    end = NomiDimens.rowInset,
                    top = NomiDimens.groupHeaderTop,
                    bottom = NomiDimens.groupHeaderBottom,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.orEmpty().uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    } else {
        Spacer(Modifier.height(NomiDimens.groupHeaderTop))
    }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NomiDimens.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
    footer?.let { Footer(it) }
}

/** Gray footnote outside the card. */
@Composable
fun Footer(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = NomiDimens.rowInset,
            end = NomiDimens.rowInset,
            top = NomiDimens.footerTop,
            bottom = NomiDimens.footerBottom,
        ),
    )
}

/** Fine divider line between two rows within a card, see [NomiDimens.dividerStartInset]. */
@Composable
fun RowDivider() {
    HorizontalDivider(
        Modifier.padding(start = NomiDimens.dividerStartInset),
        color = MaterialTheme.colorScheme.outline,
    )
}

/** A row of "Label —— right-side gray value". Serial numbers / MAC addresses, etc., are meant to be copied and allow long-press selection. */
@Composable
fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NomiDimens.rowMinHeight)
            .padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A row in the card representing an **action**: the entire row is clickable, text is left-aligned, and uses the accent color.
 * For `destructive`, the color is red; if `icon` is not null, an accent-colored icon is added to the left of the text.
 *
 * Using `TextButton` directly is not viable: its clickable area is only as wide as the text and includes its own padding,
 * which misaligns it with the regular rows above and below.
 */
@Composable
fun ActionRow(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = NomiDimens.rowMinHeight)
            .padding(horizontal = NomiDimens.rowInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(NomiDimens.actionIconSize),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = if (icon != null) Modifier.padding(start = NomiDimens.actionIconGap) else Modifier,
        )
    }
    if (divider) RowDivider()
}

/** Sub-page shell: handles only content and scrolling — the back arrow and title belong to the outer navigation bar (see `PushedPage`). */
@Composable
fun SubPage(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = NomiDimens.screenInset),
    ) { content() }
}

/** Empty state: large icon + bold title + gray description, vertically centered in the content area. */
@Composable
fun EmptyState(title: String, message: String, icon: ImageVector) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(44.dp),
        )
        // The spacing between the icon, title, and description is 12dp
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
