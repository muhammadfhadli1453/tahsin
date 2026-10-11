package com.tahsin.app.domain.gharib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

/** Source-filter vowels with known formants, so acoustic checks can be tested without recordings. */
object SyntheticSpeech {
    const val RATE = 16_000

    // F1/F2/F3 roughly as a male reciter produces them.
    val EH = doubleArrayOf(420.0, 2100.0, 2700.0) // imala ē
    val AA = doubleArrayOf(700.0, 1300.0, 2600.0) // plain ā after ه
    val HEAVY_AA = doubleArrayOf(650.0, 1000.0, 2500.0) // ā after a heavy ra
    val OO = doubleArrayOf(450.0, 850.0, 2400.0) // o, "majroha"

    /** A vowel: a 120 Hz pulse train through resonators at [formants]. */
    fun vowel(ms: Int, formants: DoubleArray): FloatArray {
        val n = RATE * ms / 1000
        var signal = DoubleArray(n) { if (it % (RATE / 120) == 0) 1.0 else 0.0 }
        for (f in formants) {
            val r = exp(-PI * 80.0 / RATE)
            val c1 = 2 * r * cos(2 * PI * f / RATE)
            val c2 = -r * r
            val out = DoubleArray(n)
            for (i in 0 until n) {
                out[i] = signal[i] + c1 * (if (i > 0) out[i - 1] else 0.0) + c2 * (if (i > 1) out[i - 2] else 0.0)
            }
            signal = out
        }
        val peak = signal.maxOf { abs(it) }
        return FloatArray(n) { (signal[it] / peak * 0.5).toFloat() }
    }

    fun gap(ms: Int) = FloatArray(RATE * ms / 1000)

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** مجراها with [middle] as the vowel after ra: ma · (ra)V · hā. */
    fun majra(middle: DoubleArray) = concat(vowel(150, AA), gap(60), vowel(350, middle), gap(50), vowel(300, AA))
}
