package com.tahsin.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

val TahsinTypography = Typography()

/**
 * Base style for mushaf text. Arabic needs far more line height than Latin, and
 * the direction has to be pinned so a mixed-script screen does not reorder words.
 */
fun arabicStyle(fontSizeSp: Float, scale: Float = 1f): TextStyle = TextStyle(
    fontSize = (fontSizeSp * scale).sp,
    lineHeight = (fontSizeSp * scale * 2.0f).sp,
    textDirection = TextDirection.Rtl,
)
