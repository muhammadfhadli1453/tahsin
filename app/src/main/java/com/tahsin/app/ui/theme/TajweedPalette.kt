package com.tahsin.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.tahsin.app.domain.text.TajweedRule

/**
 * Glyph colours for Tajweed rules.
 *
 * These sit on top of the word-status background, so the two signals stay
 * separable: the background says whether you recited the word correctly, the
 * letter colour says how the word should be pronounced. Both palettes are tuned
 * to stay legible against every status background in their own theme.
 */
data class TajweedPalette(
    val ghunnah: Color,
    val ikhfa: Color,
    val idghamGhunnah: Color,
    val idghamNoGhunnah: Color,
    val iqlab: Color,
    val qalqalah: Color,
    val maddObligatory: Color,
    val maddPermissible: Color,
    val maddNecessary: Color,
    val silent: Color,
) {
    fun colorFor(rule: TajweedRule): Color = when (rule) {
        TajweedRule.Ghunnah -> ghunnah
        TajweedRule.IkhfaNoon, TajweedRule.IkhfaShafawi -> ikhfa
        TajweedRule.IdghamGhunnah, TajweedRule.IdghamShafawi -> idghamGhunnah
        TajweedRule.IdghamNoGhunnah -> idghamNoGhunnah
        TajweedRule.Iqlab -> iqlab
        TajweedRule.Qalqalah -> qalqalah
        TajweedRule.MaddObligatory -> maddObligatory
        TajweedRule.MaddPermissible -> maddPermissible
        TajweedRule.MaddNecessary -> maddNecessary
        TajweedRule.Silent -> silent
    }
}

/** Deep, saturated hues that hold up against the pale status backgrounds. */
val LightTajweedPalette = TajweedPalette(
    ghunnah = Color(0xFF00796B),
    ikhfa = Color(0xFF6A1B9A),
    idghamGhunnah = Color(0xFF1565C0),
    idghamNoGhunnah = Color(0xFF37474F),
    iqlab = Color(0xFFAD1457),
    qalqalah = Color(0xFFE65100),
    maddObligatory = Color(0xFFC62828),
    maddPermissible = Color(0xFFD84315),
    maddNecessary = Color(0xFF8E0000),
    silent = Color(0xFF9E9E9E),
)

/** Light, desaturated hues for dark surfaces. */
val DarkTajweedPalette = TajweedPalette(
    ghunnah = Color(0xFF4DD0C4),
    ikhfa = Color(0xFFCE93D8),
    idghamGhunnah = Color(0xFF81C7F5),
    idghamNoGhunnah = Color(0xFFB0BEC5),
    iqlab = Color(0xFFF48FB1),
    qalqalah = Color(0xFFFFB74D),
    maddObligatory = Color(0xFFEF9A9A),
    maddPermissible = Color(0xFFFFAB91),
    maddNecessary = Color(0xFFFF8A80),
    silent = Color(0xFF6E6E6E),
)

val LocalTajweedPalette = staticCompositionLocalOf { LightTajweedPalette }
