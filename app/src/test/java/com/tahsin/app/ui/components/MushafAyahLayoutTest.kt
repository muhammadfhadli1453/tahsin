package com.tahsin.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tahsin.app.domain.model.ExpectedWord
import com.tahsin.app.domain.model.WordStatus
import com.tahsin.app.domain.text.ArabicNormalizer
import com.tahsin.app.ui.theme.TahsinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A wrapped ayah must read like a mushaf page: first line on top, each line
 * running right to left. It used to lay out the reversed word list left to
 * right, which kept each line's words in order but put the *end* of the ayah
 * on the first line — reported on a phone for 1:7.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MushafAyahLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    // Al-Fatihah 1:7, long enough to wrap at phone width.
    private val words = ArabicNormalizer.displayWords("صِرَٰطَ ٱلَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ ٱلْمَغْضُوبِ عَلَيْهِمْ وَلَا ٱلضَّآلِّينَ")
        .mapIndexed { index, uthmani ->
            val normalized = ArabicNormalizer.normalize(uthmani)
            ExpectedWord(index, 1, 7, index, uthmani, normalized, listOf(normalized))
        }

    @Test
    fun `a wrapped ayah starts on the first line at the right edge`() {
        compose.setContent {
            TahsinTheme {
                Box(Modifier.width(320.dp)) {
                    MushafAyah(words = words, statuses = List(words.size) { WordStatus.Pending }, fontScale = 1f)
                }
            }
        }

        val first = compose.onNodeWithText(words.first().uthmani).getBoundsInRoot()
        val second = compose.onNodeWithText(words[1].uthmani).getBoundsInRoot()
        val last = compose.onNodeWithText(words.last().uthmani).getBoundsInRoot()

        assertTrue("ayah did not wrap, so the test proves nothing", first.top != last.top)
        assertTrue("the ayah's first word is not on the first line", first.top < last.top)
        assertTrue("the first word is not to the right of the second", first.left > second.left)
    }
}
