package com.nomily.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Font size table — fixed point sizes pinned by hand for each tier, not Material's defaults.
 *
 * Material's default tiers sit 1–2sp off from these sizes and carry 0.25–0.5sp of tracking,
 * making text both slightly larger and slightly looser. Pin them down here: **changing this
 * one table changes the global font sizes** —
 * no more ad-hoc sp values scattered across the code.
 *
 * | Material token | size / line height |
 * |----------------|--------------------|
 * | headlineLarge  | 34 / 41   |
 * | headlineMedium | 28 / 34   |
 * | headlineSmall  | 22 / 28   |
 * | titleLarge     | 22 / 28   |
 * | titleMedium    | 17 / 22 semibold |
 * | titleSmall     | 16 / 21   |
 * | bodyLarge      | 17 / 22   |  ← main text of table rows
 * | bodyMedium     | 15 / 20   |
 * | bodySmall      | 13 / 18   |
 * | labelLarge     | 17 / 22   |  ← buttons
 * | labelMedium    | 13 / 18   |  ← section headers
 * | labelSmall     | 11 / 13   |  ← inline secondary text
 *
 * Tracking is always 0: no extra tracking is needed at these sizes — Material's default tracking
 * would stretch the same line of Chinese text half a character wider.
 */
private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

val Typography = Typography(
    headlineLarge = textStyle(34, 41),
    headlineMedium = textStyle(28, 34),
    headlineSmall = textStyle(22, 28),
    titleLarge = textStyle(22, 28),
    titleMedium = textStyle(17, 22, FontWeight.SemiBold),
    titleSmall = textStyle(16, 21, FontWeight.Medium),
    bodyLarge = textStyle(17, 22),
    bodyMedium = textStyle(15, 20),
    bodySmall = textStyle(13, 18),
    labelLarge = textStyle(17, 22, FontWeight.Medium),
    labelMedium = textStyle(13, 18, FontWeight.Medium),
    labelSmall = textStyle(11, 13, FontWeight.Medium),
)
