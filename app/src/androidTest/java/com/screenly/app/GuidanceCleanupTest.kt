package com.screenly.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.screenly.app.ai.LocalInference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI and production lifecycle callbacks; not an Android-bound service/OEM stress test. */
@RunWith(AndroidJUnit4::class)
class GuidanceCleanupTest {
    @Test
    fun reconnectAndDestroyCloseTheirOwnedModelAfterCancellation() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guidanceCleanup") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var service: ScreenlyAccessibilityService
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun overlay(): ScreenlyOverlay = ScreenlyAccessibilityService::class.java.getDeclaredField("overlay")
            .apply { isAccessible = true }.get(service) as ScreenlyOverlay
        fun inference(owner: ScreenlyOverlay): LocalInference = ScreenlyOverlay::class.java.getDeclaredField("inference")
            .apply { isAccessible = true }.get(owner) as LocalInference
        fun scope(owner: ScreenlyOverlay): CoroutineScope = ScreenlyOverlay::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(owner) as CoroutineScope
        suspend fun assertClosed(runtime: LocalInference) {
            try { runtime.generate("Reply YES."); fail("Disposed owner still accepts inference") }
            catch (_: IllegalStateException) { }
        }
        onMain {
            service = ScreenlyAccessibilityService()
            // Attach a test application context without binding to the OS or drawing overlays.
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(service, instrumentation.targetContext)
            ScreenlyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(service)
        }
        try {
            val first = overlay()
            val firstRuntime = inference(first)
            firstRuntime.initialize()
            val entered = CompletableDeferred<Unit>()
            val pending = scope(first).async {
                entered.complete(Unit)
                firstRuntime.generate("Reply exactly YES.")
            }
            entered.await()
            delay(100) // Does not assert that native generation is interruptible.
            onMain { ScreenlyAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(service) }
            withTimeout(120_000) { scope(first).coroutineContext[Job]!!.join() }
            assertTrue(pending.isCancelled)
            assertClosed(firstRuntime)
            val replacement = overlay()
            assertNotSame(first, replacement)
            val replacementRuntime = inference(replacement)
            assertNotSame(firstRuntime, replacementRuntime)
            replacementRuntime.initialize()
            assertTrue(replacementRuntime.generate("Reply exactly YES.").isNotBlank())
            onMain { service.onDestroy() }
            withTimeout(120_000) { scope(replacement).coroutineContext[Job]!!.join() }
            assertClosed(replacementRuntime)
            onMain { service.onDestroy() } // Idempotent disconnect/cleanup.
        } finally {
            onMain { service.onDestroy() }
        }
    }
}
