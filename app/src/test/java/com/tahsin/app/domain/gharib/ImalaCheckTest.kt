package com.tahsin.app.domain.gharib

import com.tahsin.app.domain.gharib.SyntheticSpeech.AA
import com.tahsin.app.domain.gharib.SyntheticSpeech.EH
import com.tahsin.app.domain.gharib.SyntheticSpeech.HEAVY_AA
import com.tahsin.app.domain.gharib.SyntheticSpeech.OO
import com.tahsin.app.domain.gharib.SyntheticSpeech.gap
import com.tahsin.app.domain.gharib.SyntheticSpeech.majra
import com.tahsin.app.domain.gharib.SyntheticSpeech.vowel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImalaCheckTest {

    private val rate = SyntheticSpeech.RATE

    @Test
    fun `tracks the second formant of a steady vowel`() {
        val track = FormantTracker.f2Track(vowel(300, EH), rate)
        val median = track.sorted()[track.size / 2]
        assertEquals(2100.0, median, 150.0)
    }

    @Test
    fun `finds the roots of a known polynomial`() {
        // (z - 0.5)(z + 0.25)(z^2 + 0.81) = z^4 - 0.25 z^3 + 0.685 z^2 - 0.2025 z - 0.10125
        val roots = FormantTracker.polynomialRoots(doubleArrayOf(1.0, -0.25, 0.685, -0.2025, -0.10125))
        val sorted = roots.sortedWith(compareBy({ Math.round(it.re * 1e6) }, { it.im }))
        assertEquals(-0.25, sorted[0].re, 1e-6)
        assertEquals(0.0, sorted[1].re, 1e-6)
        assertEquals(-0.9, sorted[1].im, 1e-6)
        assertEquals(0.9, sorted[2].im, 1e-6)
        assertEquals(0.5, sorted[3].re, 1e-6)
    }

    @Test
    fun `imala reads as correct`() {
        assertEquals(GharibVerdict.Correct, ImalaCheck.measure(majra(EH), rate).verdict)
    }

    @Test
    fun `plain heavy ra reads as wrong`() {
        assertEquals(GharibVerdict.Wrong, ImalaCheck.measure(majra(HEAVY_AA), rate).verdict)
    }

    @Test
    fun `o instead of imala reads as wrong`() {
        assertEquals(GharibVerdict.Wrong, ImalaCheck.measure(majra(OO), rate).verdict)
    }

    @Test
    fun `too little voicing is unclear, never wrong`() {
        assertEquals(GharibVerdict.Unclear, ImalaCheck.measure(vowel(120, AA), rate).verdict)
        assertEquals(GharibVerdict.Unclear, ImalaCheck.measure(gap(500), rate).verdict)
    }

    /**
     * The thresholds were chosen on master recitations: 11:41 مَجْر۪ىٰهَا (imala) and
     * 79:43 ذِكْرَاهَا (the same "-rāhā" ending without it). Those word clips are
     * not part of the repo; point GHARIB_CLIPS at a folder holding them and an
     * expected.txt listing their names to run this.
     */
    @Test
    fun `never accuses a master's imala and catches every plain ra`() {
        val dir = System.getenv("GHARIB_CLIPS")?.let(::File)
        assumeTrue("GHARIB_CLIPS not set", dir?.isDirectory == true)
        val names = File(dir, "expected.txt").readLines().filter { it.isNotBlank() }.map { it.split(" ")[0] }
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        for (name in names) {
            val m = ImalaCheck.measure(readWav(File(dir, "$name.wav")), rate)
            report.append("$name ${m.deltasHz.map { it?.toInt() }} ${m.verdict}\n")
            if (name.startsWith("imala") && m.verdict == GharibVerdict.Wrong) failures += "$name judged wrong"
            if (name.startsWith("plainraha") && m.verdict != GharibVerdict.Wrong) failures += "$name not caught"
        }
        println(report)
        assertEquals(report.toString(), emptyList<String>(), failures)
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(data.remaining()) { data.get(it) / 32768f }
    }
}
