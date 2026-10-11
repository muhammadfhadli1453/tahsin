package com.tahsin.app

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tahsin.app.domain.recitation.EngineTuning
import com.tahsin.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/**
 * Times one live-recognition pass on the device, with the engine's real decode
 * options, on real recitation audio, across thread counts.
 *
 * Big.LITTLE phones are why thread count matters: ggml splits each matrix
 * multiply evenly across threads and waits for the slowest, so threads that
 * land on little cores can make a pass slower, not faster.
 *
 * Needs files/models/<model>.bin and files/test-fixtures/alafasy-fatihah.wav in
 * the app's private storage. Logs with tag TahsinBench.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchmarkTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun timeLivePassesAcrossThreadCounts() {
        val model = File(context.filesDir, "models").listFiles { f -> f.extension == "bin" }
            ?.maxByOrNull { it.length() }
        val wav = File(context.filesDir, "test-fixtures/alafasy-fatihah.wav")
        assumeTrue("no model", model != null)
        assumeTrue("no fixture", wav.exists())

        // Foreground, so vendor power managers do not freeze the process.
        ActivityScenario.launch(MainActivity::class.java).use {
            val audio = readFloat(wav)
            val whisper = WhisperContext.createFromFile(model!!.absolutePath)
            Log.i(TAG, "model=${model.name} cores=${Runtime.getRuntime().availableProcessors()}")
            try {
                runBlocking {
                    // Warm-up so the first measured pass is not paying page-in costs.
                    whisper.transcribe(audio.copyOfRange(0, 16_000 * 3), EngineTuning.LiveDecode)
                    for (seconds in listOf(2.5f, 5f)) {
                        // From ayah 2 onwards: real speech, not the opening silence.
                        val start = 16_000 * 7
                        val window = audio.copyOfRange(start, start + (16_000 * seconds).toInt())
                        for (threads in listOf(2, 3, 4, 6, 8)) {
                            val options = EngineTuning.LiveDecode.copy(
                                threads = threads,
                                audioContext = window.size / 320 + 32,
                                maxTokens = (seconds * 30).toInt() + 16,
                            )
                            var text = ""
                            val ms = measureTimeMillis { text = whisper.transcribe(window, options).text }
                            Log.i(
                                TAG,
                                "window=${seconds}s threads=$threads -> ${ms}ms " +
                                    "(${"%.2f".format(ms / (seconds * 1000.0))}x realtime) \"$text\"",
                            )
                        }
                    }
                }
            } finally {
                runBlocking { whisper.release() }
            }
        }
    }

    private companion object {
        const val TAG = "TahsinBench"

        fun readFloat(file: File): FloatArray {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val size = buffer.getInt(pos + 4)
                if (String(bytes, pos, 4) == "data") {
                    val count = (minOf(bytes.size, pos + 8 + size) - pos - 8) / 2
                    return FloatArray(count) { buffer.getShort(pos + 8 + it * 2) / 32768f }
                }
                pos += 8 + size + (size and 1)
            }
            error("no data chunk")
        }
    }
}
