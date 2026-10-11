package com.tahsin.app.domain.gharib

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-formant (F2) tracking by linear prediction, the classic way vowel
 * quality is measured: the vocal tract is modelled as an all-pole filter, and
 * the poles' angles are its resonances. F2 is what separates front vowels
 * (i, ē — high F2) from open and back ones (ā, o, u — low F2).
 */
object FormantTracker {
    /** Analysis rate: formants of interest sit below 3 kHz, and LPC order 12 suits 10 kHz. */
    const val ANALYSIS_RATE = 10_000
    private const val LPC_ORDER = 12
    private const val FRAME = 250 // 25 ms
    private const val HOP = 100 // 10 ms
    private const val PRE_EMPHASIS = 0.97
    private const val MAX_BANDWIDTH_HZ = 500.0
    private const val VOICED_ENERGY_FRACTION = 0.3

    /**
     * F2 of every loud-enough 10 ms frame of [samples], in order. Quiet frames
     * (closures, pauses) and frames with no clear F2 are left out.
     */
    fun f2Track(samples: FloatArray, sampleRate: Int): DoubleArray {
        val x = resample(samples, sampleRate, ANALYSIS_RATE)
        if (x.size < FRAME) return DoubleArray(0)
        val starts = (0 until x.size - FRAME step HOP).toList()
        val energy = DoubleArray(starts.size) { k ->
            var sum = 0.0
            for (i in starts[k] until starts[k] + FRAME) sum += x[i] * x[i]
            sqrt(sum / FRAME)
        }
        val threshold = VOICED_ENERGY_FRACTION * percentile(energy, 95.0)
        val out = ArrayList<Double>()
        for ((k, start) in starts.withIndex()) {
            if (energy[k] < threshold) continue
            val f = formants(x, start, ANALYSIS_RATE) ?: continue
            if (f.second in 700.0..3000.0) out += f.second
        }
        return out.toDoubleArray()
    }

    /** The two lowest well-defined resonances (F1, F2) of one frame, or null. */
    internal fun formants(x: DoubleArray, start: Int, fs: Int): Pair<Double, Double>? {
        val frame = DoubleArray(FRAME) { i ->
            val pre = if (i == 0) x[start] else x[start + i] - PRE_EMPHASIS * x[start + i - 1]
            pre * (0.54 - 0.46 * cos(2 * PI * i / (FRAME - 1)))
        }
        val a = lpc(frame, LPC_ORDER) ?: return null
        val candidates = polynomialRoots(a)
            .filter { it.im > 0 }
            .mapNotNull { z ->
                val f = atan2(z.im, z.re) * fs / (2 * PI)
                val bandwidth = -fs / PI * ln(hypot(z.re, z.im))
                f.takeIf { it > 150 && it < fs / 2 - 100 && bandwidth < MAX_BANDWIDTH_HZ }
            }
            .sorted()
        return if (candidates.size >= 2) candidates[0] to candidates[1] else null
    }

    /** Autocorrelation LPC via Levinson-Durbin; `a[0] == 1`. */
    internal fun lpc(frame: DoubleArray, order: Int): DoubleArray? {
        val r = DoubleArray(order + 1) { lag ->
            var sum = 0.0
            for (i in 0 until frame.size - lag) sum += frame[i] * frame[i + lag]
            sum
        }
        if (r[0] <= 0) return null
        val a = DoubleArray(order + 1).also { it[0] = 1.0 }
        var error = r[0]
        for (i in 1..order) {
            var acc = r[i]
            for (j in 1 until i) acc += a[j] * r[i - j]
            val k = -acc / error
            val previous = a.copyOf()
            for (j in 1 until i) a[j] = previous[j] + k * previous[i - j]
            a[i] = k
            error *= 1 - k * k
            if (error <= 0) return null
        }
        return a
    }

    internal data class Complex(val re: Double, val im: Double) {
        operator fun plus(o: Complex) = Complex(re + o.re, im + o.im)
        operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: Complex): Complex {
            val d = o.re * o.re + o.im * o.im
            return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        val magnitude get() = hypot(re, im)
    }

    /**
     * Roots of `a[0] z^n + a[1] z^(n-1) + … + a[n]` by Durand-Kerner iteration,
     * which converges reliably for the low-order, all-roots-inside-the-unit-circle
     * polynomials LPC produces.
     */
    internal fun polynomialRoots(a: DoubleArray): List<Complex> {
        val n = a.size - 1
        if (n < 1) return emptyList()
        val c = DoubleArray(a.size) { a[it] / a[0] }
        fun eval(z: Complex): Complex {
            var acc = Complex(1.0, 0.0)
            for (i in 1..n) acc = acc * z + Complex(c[i], 0.0)
            return acc
        }
        val seed = Complex(0.4, 0.9)
        val roots = Array(n) { k ->
            var p = Complex(1.0, 0.0)
            repeat(k) { p *= seed }
            p
        }
        repeat(500) {
            var moved = 0.0
            for (i in 0 until n) {
                var denominator = Complex(1.0, 0.0)
                for (j in 0 until n) if (j != i) denominator *= roots[i] - roots[j]
                if (denominator.magnitude == 0.0) denominator = Complex(1e-12, 0.0)
                val step = eval(roots[i]) / denominator
                roots[i] = roots[i] - step
                moved = maxOf(moved, step.magnitude)
            }
            if (moved < 1e-12) return roots.toList()
        }
        return roots.toList()
    }

    /** Band-limited resampling (Hann-windowed sinc), enough for formant work. */
    internal fun resample(x: FloatArray, from: Int, to: Int): DoubleArray {
        if (from == to) return DoubleArray(x.size) { x[it].toDouble() }
        val outSize = (x.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        val cutoff = minOf(1.0, to.toDouble() / from) * 0.95
        val half = 16
        return DoubleArray(outSize) { n ->
            val t = n * ratio
            val centre = t.toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in centre - half + 1..centre + half) {
                if (k < 0 || k >= x.size) continue
                val d = t - k
                val window = 0.5 + 0.5 * cos(PI * d / half)
                val sinc = if (abs(d) < 1e-9) 1.0 else sin(PI * cutoff * d) / (PI * cutoff * d)
                val w = window * sinc
                acc += x[k] * w
                norm += w
            }
            if (norm == 0.0) 0.0 else acc / norm
        }
    }

    internal fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = p / 100.0 * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
    }

    /** Median over a window of [k] frames, edges padded by repetition. */
    fun medianFilter(y: DoubleArray, k: Int = 5): DoubleArray {
        val h = k / 2
        return DoubleArray(y.size) { i ->
            DoubleArray(k) { j -> y[(i - h + j).coerceIn(0, y.size - 1)] }.sorted()[h]
        }
    }

    /** A stretch of frames whose F2 holds steady: a sustained vowel. */
    data class Run(val start: Int, val end: Int, val median: Double) {
        val length get() = end - start
    }

    /** Maximal runs in which every frame stays within [tolerance] of the run's median. */
    fun stableRuns(y: DoubleArray, tolerance: Double = 0.07, minLength: Int = 6): List<Run> {
        val runs = mutableListOf<Run>()
        var a = 0
        while (a < y.size) {
            var b = a + 1
            while (b < y.size) {
                val m = median(y, a, b)
                if (abs(y[b] - m) > tolerance * m) break
                b++
            }
            if (b - a >= minLength) runs += Run(a, b, median(y, a, b))
            a = b
        }
        return runs
    }

    private fun median(y: DoubleArray, from: Int, to: Int): Double {
        val s = y.copyOfRange(from, to).sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
}
