package com.tahsin.app.domain.text

import kotlin.math.max
import kotlin.math.min

/**
 * Similarity between a recited word and an expected word, in `0.0..1.0`.
 *
 * A plain edit distance is too blunt here: a base Whisper model routinely
 * confuses Arabic letters that sound alike (ه/ح, ق/ك, س/ص/ث), and treating those
 * as full substitutions produces a flood of phantom mistakes. Those confusions
 * are charged a reduced cost instead, so genuine misreadings still stand out
 * while transcription noise does not.
 */
object WordSimilarity {

    /** Letters that a Whisper-class ASR confuses most often, by shared place of articulation. */
    private val confusionGroups = listOf(
        "هح",
        "اع",
        "قك",
        "تط",
        "سصث",
        "ذزظض",
        "دض",
        "غخ",
        "جز",
        "بف",
        "من",
        "لر",
        "وي",
    )

    private val groupOf: Map<Char, Int> = buildMap {
        confusionGroups.forEachIndexed { index, group ->
            group.forEach { ch -> put(ch, index) }
        }
    }

    private const val NEAR_SUBSTITUTION_COST = 0.4
    private const val FULL_SUBSTITUTION_COST = 1.0
    private const val GAP_COST = 1.0

    /** Highest similarity between [spoken] and any accepted form of the expected word. */
    fun best(spoken: String, expectedVariants: List<String>): Double {
        var best = 0.0
        for (variant in expectedVariants) {
            val score = of(spoken, variant)
            if (score > best) best = score
            if (best == 1.0) break
        }
        return best
    }

    fun of(spoken: String, expected: String): Double {
        if (spoken.isEmpty() && expected.isEmpty()) return 1.0
        if (spoken.isEmpty() || expected.isEmpty()) return 0.0
        if (spoken == expected) return 1.0

        val distance = weightedDistance(spoken, expected)
        val worstCase = max(spoken.length, expected.length).toDouble()
        val score = 1.0 - distance / worstCase

        // A one-letter word matching a long word scores misleadingly high under
        // plain normalisation, so penalise large length gaps.
        val lengthRatio = min(spoken.length, expected.length).toDouble() /
            max(spoken.length, expected.length).toDouble()
        return (score * (0.7 + 0.3 * lengthRatio)).coerceIn(0.0, 1.0)
    }

    private fun substitutionCost(a: Char, b: Char): Double {
        if (a == b) return 0.0
        val groupA = groupOf[a] ?: return FULL_SUBSTITUTION_COST
        val groupB = groupOf[b] ?: return FULL_SUBSTITUTION_COST
        return if (groupA == groupB) NEAR_SUBSTITUTION_COST else FULL_SUBSTITUTION_COST
    }

    private fun weightedDistance(a: String, b: String): Double {
        var previous = DoubleArray(b.length + 1) { it * GAP_COST }
        var current = DoubleArray(b.length + 1)

        for (i in a.indices) {
            current[0] = (i + 1) * GAP_COST
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + GAP_COST,
                    previous[j + 1] + GAP_COST,
                    previous[j] + substitutionCost(a[i], b[j]),
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
