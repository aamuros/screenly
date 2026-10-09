package com.screenly.app.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Native compatibility gate. Two different images must change the answer to the SAME prompt. */
@RunWith(AndroidJUnit4::class)
class MultimodalInferenceTest {
    @Test fun genuineImageInferenceAndLifecycle() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Provision the licensed model and pass -e visionSmoke true.",
            InstrumentationRegistry.getArguments().getString("visionSmoke") == "true")
        val context = instrumentation.targetContext
        val certificate = VisionModel.certificationIn(context.noBackupFilesDir)
        certificate.delete() // A failed rerun cannot leave an old certification behind.
        val runtime = LiteRtMultimodalInference(context, nativeVerification = true)
        fun record(key: String, value: String) = instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
        record("device", "${Build.MODEL}; API ${Build.VERSION.SDK_INT}; ${Build.SUPPORTED_ABIS.joinToString()}")
        record("model_sha256", VisionModel.SHA256)
        record("runtime", "LiteRT-LM ${VisionModel.RUNTIME}; CPU4 + GPU vision; 4096 tokens")
        try {
            val start = SystemClock.elapsedRealtime()
            runtime.initialize()
            record("cold_initialize_ms", (SystemClock.elapsedRealtime() - start).toString())
            val warm = SystemClock.elapsedRealtime()
            runtime.initialize()
            record("reuse_initialize_ms", (SystemClock.elapsedRealtime() - warm).toString())
            for ((name, color) in listOf("RED" to Color.RED, "BLUE" to Color.BLUE)) {
                val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                val bytes = try {
                    bitmap.eraseColor(color)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally { bitmap.recycle() }
                try {
                    val before = SystemClock.elapsedRealtime()
                    val response = runtime.generate("What is the solid background color of this image? Reply exactly RED or BLUE.", bytes)
                    record("${name.lowercase()}_inference_ms", (SystemClock.elapsedRealtime() - before).toString())
                    record("${name.lowercase()}_response", response)
                    val other = if (name == "RED") "BLUE" else "RED"
                    assertTrue("Image contents must determine the response.",
                        Regex("\\b$name\\b", RegexOption.IGNORE_CASE).containsMatchIn(response) &&
                            !Regex("\\b$other\\b", RegexOption.IGNORE_CASE).containsMatchIn(response))
                    record("${name.lowercase()}_pss_kib", Debug.getPss().toString())
                } finally { bytes.fill(0) }
            }
            runtime.unload()
            runtime.initialize()
            val text = runtime.generate("Reply exactly READY.")
            assertTrue(text.isNotBlank())
        } finally { runtime.close(); runtime.close() }
        certificate.writeText(VisionModel.certification())
        record("image_gate", "PASS: two distinct images, engine reuse, text input, cleanup")
    }
}
