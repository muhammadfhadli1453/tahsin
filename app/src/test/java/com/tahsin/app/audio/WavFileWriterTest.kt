package com.tahsin.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sampleRate = 16_000

    private fun declaredDataBytes(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(44)
            raf.readFully(head)
            return ByteBuffer.wrap(head, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        }
    }

    private fun declaredRiffSize(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(44)
            raf.readFully(head)
            return ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        }
    }

    private fun frame(samples: Int) = ShortArray(samples) { (it % 100).toShort() }

    @Test
    fun `a closed file declares exactly the audio it contains`() {
        val file = folder.newFile("a.wav")
        WavFileWriter(file, sampleRate).use { writer ->
            repeat(10) { writer.write(frame(1_600), 1_600) }
        }
        val audioBytes = file.length() - 44
        assertEquals(audioBytes, declaredDataBytes(file))
        assertEquals(audioBytes + 36, declaredRiffSize(file))
    }

    @Test
    fun `the header stays truthful while recording is still in progress`() {
        // This is the regression that made a 104-second recitation play as 0:00:
        // the header was only written on close, so an interrupted session left
        // valid audio declared as zero bytes.
        val file = folder.newFile("b.wav")
        val writer = WavFileWriter(file, sampleRate)
        try {
            // Five seconds of audio, well past the refresh interval.
            repeat(50) { writer.write(frame(1_600), 1_600) }
            val declared = declaredDataBytes(file)
            assertTrue("header still declares $declared bytes mid-recording", declared > 0)
            assertTrue(declared <= file.length() - 44)
        } finally {
            writer.close()
        }
    }

    @Test
    fun `audio written after a header refresh is not overwritten`() {
        val file = folder.newFile("c.wav")
        WavFileWriter(file, sampleRate).use { writer ->
            repeat(60) { writer.write(frame(1_600), 1_600) }
        }
        assertEquals(60L * 1_600 * 2, declaredDataBytes(file))
        assertEquals(60L * 1_600 * 2 + 44, file.length())
    }

    @Test
    fun `repair fixes a header that under-reports its audio`() {
        val file = folder.newFile("d.wav")
        val writer = WavFileWriter(file, sampleRate)
        repeat(4) { writer.write(frame(1_600), 1_600) }
        // Simulate the old behaviour: blank the declared sizes without closing.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4); raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(36).array())
            raf.seek(40); raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        }
        assertEquals(0L, declaredDataBytes(file))

        assertTrue(WavFileWriter.repairIfTruncated(file, sampleRate))
        assertEquals(file.length() - 44, declaredDataBytes(file))
        assertEquals(file.length() - 8, declaredRiffSize(file))
    }

    @Test
    fun `repair leaves a healthy file alone`() {
        val file = folder.newFile("e.wav")
        WavFileWriter(file, sampleRate).use { writer -> writer.write(frame(1_600), 1_600) }
        assertFalse(WavFileWriter.repairIfTruncated(file, sampleRate))
    }

    @Test
    fun `repair ignores files that are not wav`() {
        val file = folder.newFile("f.bin")
        file.writeBytes(ByteArray(2_000) { 7 })
        assertFalse(WavFileWriter.repairIfTruncated(file, sampleRate))
    }

    @Test
    fun `repair ignores an empty or header-only file`() {
        val empty = folder.newFile("g.wav")
        assertFalse(WavFileWriter.repairIfTruncated(empty, sampleRate))
        val headerOnly = folder.newFile("h.wav")
        WavFileWriter(headerOnly, sampleRate).close()
        assertFalse(WavFileWriter.repairIfTruncated(headerOnly, sampleRate))
    }

    @Test
    fun `writing after close is ignored`() {
        val file = folder.newFile("i.wav")
        val writer = WavFileWriter(file, sampleRate)
        writer.write(frame(1_600), 1_600)
        writer.close()
        val lengthAfterClose = file.length()
        writer.write(frame(1_600), 1_600)
        assertEquals(lengthAfterClose, file.length())
    }

    @Test
    fun `close is idempotent`() {
        val file = folder.newFile("j.wav")
        val writer = WavFileWriter(file, sampleRate)
        writer.write(frame(800), 800)
        writer.close()
        writer.close()
        assertEquals(800L * 2, declaredDataBytes(file))
    }
}
