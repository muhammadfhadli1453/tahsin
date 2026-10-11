package com.tahsin.app.domain.gharib

import com.tahsin.app.domain.model.ExpectedWord

/**
 * Keeps track of the gharib words in a passage and when each can be checked.
 *
 * A check needs that word's audio whole, located precisely, which takes a
 * heavier pass than live tracking does. Gharib words are rare, so that cost is
 * paid only around them: the word-timing model is wanted from the moment the
 * cursor enters a gharib word's ayah, and the check runs once the reciter has
 * moved past the word (or paused, or finished).
 */
class GharibWatch(expected: List<ExpectedWord>) {

    class Target(val word: ExpectedWord, val kind: GharibKind) {
        /** First sample of the window in which the cursor reached this word; -1 until then. */
        var clipStartSample: Long = -1L
            internal set
        var checked: Boolean = false
            internal set
    }

    val targets: List<Target> = expected.mapNotNull { word -> GharibKind.of(word)?.let { Target(word, it) } }

    private val ayahStart: Map<Int, Int> = expected
        .groupBy { it.surahId * 1000 + it.ayahNumber }
        .mapValues { (_, words) -> words.minOf { it.position } }

    val isEmpty: Boolean get() = targets.isEmpty()

    /** True while the cursor is in the ayah of a gharib word not yet checked. */
    fun wantsAligner(cursor: Int): Boolean = targets.any { target ->
        !target.checked && cursor >= ayahStartOf(target)
    }

    /** Call after every pass with where the cursor ended up and where that pass's audio began. */
    fun onPass(cursor: Int, windowStartSample: Long) {
        for (target in targets) {
            if (target.clipStartSample < 0 && cursor >= target.word.position) {
                target.clipStartSample = windowStartSample
            }
        }
    }

    /**
     * Targets whose word has been heard in full: the reciter is two words past
     * it, or one word past and pausing, or the passage is over.
     */
    fun due(cursor: Int, speechPaused: Boolean, finished: Boolean): List<Target> = targets.filter { target ->
        val position = target.word.position
        !target.checked && target.clipStartSample >= 0 && (
            cursor >= position + 2 ||
                (cursor >= position + 1 && (speechPaused || finished)) ||
                finished
            )
    }

    fun markChecked(target: Target) {
        target.checked = true
    }

    private fun ayahStartOf(target: Target): Int =
        ayahStart[target.word.surahId * 1000 + target.word.ayahNumber] ?: target.word.position
}
