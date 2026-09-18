package com.nomily.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral gray-white background; group cards use a gray one shade lighter/darker than the background.
// The accent color is the dark cyan #00475A defined by this palette
// (not the default blue #007AFF).
// The dark variant is a brightened version of the same hue: #00475A on #1C1C1E has only 1.66:1 contrast.
// In dark mode, text and icons using the accent color are barely readable (WCAG requires 4.5:1 for body text).
// Switch to #0097BF (5.0:1 contrast against #1C1C1E).
private val AppAccent = Color(0xFF00475A)
private val AppAccentDark = Color(0xFF0097BF)
/** The switch color is fixed to this green, independent of the accent color; Material defaults to using primary, so it must be explicitly overridden. */
val AppSwitchGreen = Color(0xFF34C759)
private val AppGroupedBg = Color(0xFFF2F2F7)     // Group page background
private val AppGroupedBgDark = Color(0xFF000000)
private val AppCard = Color(0xFFFFFFFF)          // Background color of the group card
private val AppCardDark = Color(0xFF1C1C1E)
// Semantic color light screenshot color sampling verification: separator line measured (196,199,200),
// matches reference value (198,198,200), indicating no deviation in the screenshot color pipeline.
private val AppLabel = Color(0xFF000000)             // Body text (pure black on light backgrounds, not #1C1C1E)
private val AppLabelDark = Color(0xFFFFFFFF)
private val AppSecondaryLabel = Color(0xFF8E8E93)    // Secondary text
private val AppSecondaryLabelDark = Color(0xFF98989F)
private val AppSeparator = Color(0xFFC6C6C8)         // Separator and trailing arrow
private val AppSeparatorDark = Color(0xFF38383A)
/** Background color of unselected segments in the segmented control: gray groove + white selected block. Material's default is exactly the opposite. */
val AppFillTertiary = Color(0xFFE5E5EA)
val AppFillTertiaryDark = Color(0xFF2C2C2E)

private val DarkColorScheme = darkColorScheme(
    primary = AppAccentDark,
    onPrimary = Color.White,
    secondary = AppAccentDark,
    background = AppGroupedBgDark,
    onBackground = AppLabelDark,
    surface = AppCardDark,
    onSurface = AppLabelDark,
    surfaceVariant = AppCardDark,
    onSurfaceVariant = AppSecondaryLabelDark,
    outline = AppSeparatorDark,
    error = Color(0xFFFF453A),
    surfaceContainerLowest = AppCardDark,
    surfaceContainerLow = AppCardDark,
    surfaceContainer = AppCardDark,
    surfaceContainerHigh = AppCardDark,
    surfaceContainerHighest = AppCardDark,
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFF2F2F7),
)

private val LightColorScheme = lightColorScheme(
    primary = AppAccent,
    onPrimary = Color.White,
    secondary = AppAccent,
    background = AppGroupedBg,
    onBackground = AppLabel,
    surface = AppCard,
    onSurface = AppLabel,
    surfaceVariant = AppCard,
    onSurfaceVariant = AppSecondaryLabel,
    outline = AppSeparator,
    error = Color(0xFFFF3B30),
    // M3's Card / NavigationBar indicator defaults to the surfaceContainer* color levels.
    // If not overridden, it falls back to the baseline purple, resulting in light purple cards and tab indicators on the UI.
    surfaceContainerLowest = AppCard,
    surfaceContainerLow = AppCard,
    surfaceContainer = AppCard,
    surfaceContainerHigh = AppCard,
    surfaceContainerHighest = AppCard,
    secondaryContainer = AppFillTertiary,        // Gray slot of the segmented control
    onSecondaryContainer = AppLabel,
)

/**
 * ⚠️ **Do not use Material You dynamic color**: Dynamic color adapts to the user's wallpaper (on test devices, the entire UI appeared pink),
 * so a fixed neutral color scheme is used. A `dynamicColor` toggle was previously left in place,
 * but no callers ever enabled it, so it has been removed.
 */
@Composable
fun NomilyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/** Switch color scheme: selected state is green with a white slider, unselected state is a light gray track. */
@Composable
fun appSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppSwitchGreen,
    checkedBorderColor = AppSwitchGreen,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFFE9E9EA),
    uncheckedBorderColor = Color(0xFFE9E9EA),
)
