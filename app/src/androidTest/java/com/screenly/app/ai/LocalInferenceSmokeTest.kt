package com.screenly.app.ai

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real JNI/model test; never substitutes a fake response when provisioning is missing. */
@RunWith(AndroidJUnit4::class)
class LocalInferenceSmokeTest {
    @Test
    fun generateTextOnCpu() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e localAiSmoke true after provisioning the model.", arguments.getString("localAiSmoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val model = LocalModel.fileIn(context.noBackupFilesDir)
        val offlineRequired = arguments.getString("requireOffline") == "true"

        fun checkOffline() {
            if (offlineRequired) {
                // These switches supplement manually recorded network state; they are not packet capture.
                assertTrue("Enable airplane mode.", Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0) == 1)
                assertTrue("Disable Wi-Fi.", Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
                assertTrue("Disable mobile data.", Settings.Global.getInt(context.contentResolver, "mobile_data", -1) == 0)
            }
        }

        fun record(key: String, value: String) {
            Log.i(TAG, "$key=$value")
            instrumentation.sendStatus(0, Bundle().apply { putString(key, value) })
        }

        record("device", "${Build.MANUFACTURER} ${Build.MODEL}; API ${Build.VERSION.SDK_INT}; ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        record("runtime", "LiteRT-LM 0.10.2; CPU; 4 threads; 1024 total tokens")
        record("model", "${model.absolutePath}; ${model.length()} bytes; expected SHA-256 ${LocalModel.SHA256}")
        record("offline_required", offlineRequired.toString())
        checkOffline()
        val inference = LocalInference(context)
        var phase = "initialization"
        var started = SystemClock.elapsedRealtime()
        try {
            inference.initialize()
            record("initialization_ms", (SystemClock.elapsedRealtime() - started).toString())
            record("initialization", "PASS (includes model integrity verification)")
            phase = "engine reuse"
            inference.initialize()
            for (run in 1..2) {
                phase = "generation_$run"
                started = SystemClock.elapsedRealtime()
                val response = inference.generate("What Android setting controls font size?")
                record("response_${run}_ms", (SystemClock.elapsedRealtime() - started).toString())
                assertTrue("Expected generated text.", response.isNotBlank())
                record("response_$run", response)
                record("generation_$run", "PASS")
            }
            checkOffline()
        } catch (error: Exception) {
            record("failure", "$phase after ${SystemClock.elapsedRealtime() - started} ms: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            try {
                inference.close()
                inference.close()
                record("cleanup", "PASS")
            } catch (error: Exception) {
                record("cleanup", "FAIL: ${error.message}")
                throw error
            }
        }
    }

    companion object {
        private const val TAG = "ScreenlyLocalAI"
    }
}
