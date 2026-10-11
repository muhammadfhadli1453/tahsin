package com.tahsin.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.settings.HintLevel
import com.tahsin.app.ui.theme.LocalRecitationColors
import com.tahsin.app.ui.theme.LocalTajweedPalette
import com.tahsin.app.ui.theme.arabicStyle

/**
 * Renders one ayah as individually coloured words, right-to-left.
 *
 * [hintLevel] is what makes memorisation mode work: words at or beyond the
 * cursor are masked rather than removed, so the line keeps its shape and the
 * reciter still sees how much is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MushafAyah(
    words: List<ExpectedWord>,
    statuses: List<WordStatus>,
    fontScale: Float,
    modifier: Modifier = Modifier,
    maskUpcoming: Boolean = false,
    hintLevel: HintLevel = HintLevel.Hidden,
    revealedPositions: Set<Int> = emptySet(),
    showTajweed: Boolean = false,
    onWordClick: ((ExpectedWord) -> Unit)? = null,
) {
    // Lay the row out right-to-left, as a mushaf line runs. Reversing the word
    // list inside a left-to-right row instead keeps each line's words in order
    // but flips the *lines*: once an ayah wraps, its end lands on the first line.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            // Start is the right edge in a right-to-left layout.
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (word in words) {
                val status = statuses.getOrElse(word.position) { WordStatus.Pending }
                WordChip(
                    word = word,
                    status = status,
                    fontScale = fontScale,
                    masked = maskUpcoming &&
                        status == WordStatus.Pending &&
                        word.position !in revealedPositions,
                    hintLevel = hintLevel,
                    showTajweed = showTajweed,
                    onClick = onWordClick,
                )
            }
        }
    }
}

@Composable
private fun WordChip(
    word: ExpectedWord,
    status: WordStatus,
    fontScale: Float,
    masked: Boolean,
    hintLevel: HintLevel,
    showTajweed: Boolean,
    onClick: ((ExpectedWord) -> Unit)?,
) {
    val colors = LocalRecitationColors.current
    val (targetBackground, targetForeground) = when {
        masked -> colors.hidden to colors.hidden
        status == WordStatus.Correct -> colors.correct to colors.onCorrect
        status == WordStatus.Wrong -> colors.wrong to colors.onWrong
        status == WordStatus.Missed -> colors.missed to colors.onMissed
        status == WordStatus.Unclear ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        status == WordStatus.Current -> colors.current to colors.onCurrent
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }
    val background by animateColorAsState(targetBackground, label = "wordBackground")
    val foreground by animateColorAsState(targetForeground, label = "wordForeground")

    val palette = LocalTajweedPalette.current
    // Masked words must not leak their Tajweed colouring, which would hint at the
    // letters underneath the dots.
    val display = if (masked) {
        AnnotatedString(maskedText(word.uthmani, hintLevel))
    } else if (showTajweed && word.tajweed.isNotEmpty()) {
        buildAnnotatedString {
            append(word.uthmani)
            for (span in word.tajweed) {
                if (span.start < 0 || span.end > word.uthmani.length) continue
                addStyle(
                    SpanStyle(color = palette.colorFor(span.rule)),
                    span.start,
                    span.end,
                )
            }
        }
    } else {
        AnnotatedString(word.uthmani)
    }

    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable { onClick(word) } else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = display,
            color = foreground,
            style = arabicStyle(fontSizeSp = 26f, scale = fontScale).copy(
                fontWeight = if (status == WordStatus.Current) FontWeight.SemiBold else FontWeight.Normal,
            ),
            modifier = if (masked && hintLevel == HintLevel.WordShape) Modifier.alpha(0f) else Modifier,
        )
    }
}

/**
 * Masks a word for memorisation mode. The masked forms keep roughly the width of
 * the real word so the layout does not jump when it is revealed.
 */
private fun maskedText(uthmani: String, hintLevel: HintLevel): String = when (hintLevel) {
    HintLevel.Hidden -> "•".repeat(uthmani.length.coerceIn(2, 8))
    HintLevel.FirstLetter -> uthmani.take(1) + "•".repeat((uthmani.length - 1).coerceIn(1, 7))
    HintLevel.WordShape -> uthmani // hidden by alpha, so the true width is preserved
}

@Composable
fun MicLevelBar(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalRecitationColors.current
    val target = if (active) level.coerceIn(0f, 1f) else 0f
    Box(modifier = modifier.height(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
        )
        Box(
            // Speech sits well below full scale, so amplify for a readable meter.
            Modifier
                .fillMaxWidth(fraction = (target * 3f).coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(colors.current, RoundedCornerShape(3.dp)),
        )
    }
}
