package com.tahsin.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tahsin.whisper.WhisperContext
import com.tahsin.whisper.WhisperDecodeOptions
import com.tahsin.whisper.WhisperModelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Exercises the whisper.cpp JNI bridge against a real model on a device.
 *
 * Every native entry point is resolved lazily by the JVM, so a mismatched
 * signature in `jni.c` only surfaces the first time that method is called —
 * which is at recitation time, in front of the user. This test calls each one.
 *
 * Provide the model first:
 *   adb push ggml-tiny-q5_1.bin /data/local/tmp/ggml-tiny-q5_1.bin
 * The test is skipped when it is absent so CI without a model still passes.
 */
@RunWith(AndroidJUnit4::class)
class WhisperJniTest {

    private val modelFile = File("/data/local/tmp/ggml-tiny-q5_1.bin")

    private fun requireModel(): File {
        assumeTrue(
            "push a ggml model to ${modelFile.path} to run this test",
            modelFile.exists(),
        )
        return modelFile
    }

    /** Two seconds of a 220 Hz tone: not speech, but valid 16 kHz audio. */
    private fun syntheticAudio(seconds: Float = 2f): FloatArray {
        val sampleRate = 16_000
        return FloatArray((sampleRate * seconds).toInt()) { index ->
            (0.2 * sin(2.0 * PI * 220.0 * index / sampleRate)).toFloat()
        }
    }

    @Test
    fun systemInfoCrossesTheJniBoundary() {
        val info = WhisperContext.systemInfo()
        assertNotNull(info)
        assertTrue("system info should name at least one CPU feature", info.isNotEmpty())
    }

    @Test
    fun modelLoadsAndReportsItsCapabilities() = runBlocking {
        val context = WhisperContext.createFromFile(requireModel().absolutePath)
        try {
            assertTrue("a multilingual model is required for Arabic", context.isMultilingual)
        } finally {
            context.release()
        }
    }

    @Test
    fun transcribeReturnsSegmentsWithConfidenceAndTimestamps() = runBlocking {
        val context = WhisperContext.createFromFile(requireModel().absolutePath)
        try {
            val transcript = context.transcribe(
                syntheticAudio(),
                WhisperDecodeOptions(language = "ar", beamSize = 1, bestOf = 1),
            )
            // The content of a tone is meaningless; what matters is that every
            // accessor in the bridge round-trips without an UnsatisfiedLinkError.
            for (segment in transcript.segments) {
                assertTrue(segment.avgTokenProb in 0f..1f)
                assertTrue(segment.noSpeechProb in 0f..1f)
                assertTrue(segment.endMs >= segment.startMs)
            }
            assertTrue(transcript.confidence in 0f..1f)
            assertTrue(transcript.noSpeechProb in 0f..1f)
        } finally {
            context.release()
        }
    }

    @Test
    fun beamSearchDecodingAlsoRoundTrips() = runBlocking {
        val context = WhisperContext.createFromFile(requireModel().absolutePath)
        try {
            val transcript = context.transcribe(
                syntheticAudio(seconds = 1.5f),
                WhisperDecodeOptions(beamSize = 2, tokenTimestamps = true, maxLen = 0),
            )
            assertNotNull(transcript.text)
        } finally {
            context.release()
        }
    }

    @Test
    fun audioShorterThanOneMelFrameIsRejectedWithoutCallingNative() = runBlocking {
        val context = WhisperContext.createFromFile(requireModel().absolutePath)
        try {
            assertTrue(context.transcribe(FloatArray(16)).isEmpty)
        } finally {
            context.release()
        }
    }

    @Test
    fun releaseIsIdempotent() = runBlocking {
        val context = WhisperContext.createFromFile(requireModel().absolutePath)
        context.release()
        context.release()
        assertTrue(context.transcribe(syntheticAudio(0.5f)).isEmpty)
    }

    @Test(expected = WhisperModelException::class)
    fun aMissingModelFailsWithAClearError() {
        WhisperContext.createFromFile("/data/local/tmp/definitely-not-here.bin")
    }
}
