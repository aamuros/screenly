package com.screenly.app.ai

import android.content.Context
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal enum class ImageModelAvailability { MISSING, UNVERIFIED, VERIFIED, UNSUPPORTED, FAILED }

/** Images are encoded PNG/JPEG bytes. The caller owns and erases them after inference. */
internal interface LocalMultimodalInference {
    fun availability(): ImageModelAvailability
    suspend fun initialize()
    suspend fun generate(prompt: String, image: ByteArray? = null): String
    suspend fun unload() { close() }
    suspend fun close()
}

internal object VisionModel {
    const val FILE_NAME = "gemma-3n-E2B-it-int4.litertlm"
    const val SIZE_BYTES = 3_655_827_456L
    const val SHA256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6"
    const val REVISION = "c03b6f60b8da6c5400b6838a2cf26420f80c0a01"
    const val RUNTIME = "0.10.2"
    fun fileIn(directory: File) = File(directory, "models/$FILE_NAME")
    fun certificationIn(directory: File) = File(directory, "models/vision-native-verified.txt")
    fun certification() = "$SHA256|$RUNTIME|${Build.FINGERPRINT}|CPU4+GPU|4096"
}

/** A real pinned API adapter. Unverified native code is exercised only by opt-in instrumentation. */
internal class LiteRtMultimodalInference(
    context: Context,
    private val nativeVerification: Boolean = false
) : LocalMultimodalInference {
    private val directory = context.applicationContext.noBackupFilesDir
    private val cache = File(context.applicationContext.cacheDir, "vision-ai")
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var closed = false
    private var failed = false

    override fun availability(): ImageModelAvailability {
        if (Build.VERSION.SDK_INT < 30 || "arm64-v8a" !in Build.SUPPORTED_ABIS) return ImageModelAvailability.UNSUPPORTED
        if (failed) return ImageModelAvailability.FAILED
        val model = VisionModel.fileIn(directory)
        if (!model.isFile || model.length() != VisionModel.SIZE_BYTES) return ImageModelAvailability.MISSING
        return try {
            if (VisionModel.certificationIn(directory).readText() == VisionModel.certification()) ImageModelAvailability.VERIFIED
            else ImageModelAvailability.UNVERIFIED
        } catch (_: IOException) { ImageModelAvailability.UNVERIFIED }
    }

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            if (engine != null) return@withLock
            val availability = availability()
            check(availability == ImageModelAvailability.VERIFIED ||
                (nativeVerification && availability == ImageModelAvailability.UNVERIFIED)) { "Image model has not passed native verification." }
            try {
                val model = VisionModel.fileIn(directory)
                verifyModelFile(model, VisionModel.SIZE_BYTES, VisionModel.SHA256)
                currentCoroutineContext().ensureActive()
                if (!cache.isDirectory && !cache.mkdirs()) throw IOException("Cannot create vision cache.")
                val candidate = Engine(EngineConfig(
                    modelPath = model.absolutePath, backend = Backend.CPU(numOfThreads = 4),
                    visionBackend = Backend.GPU(), maxNumTokens = 4096, maxNumImages = 1,
                    cacheDir = cache.absolutePath
                ))
                // This pinned API cannot close an engine before successful initialize().
                candidate.initialize()
                engine = candidate
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                failed = true
                throw LocalInferenceException("Image model initialization failed.", error)
            } catch (error: LinkageError) {
                failed = true
                throw LocalInferenceException("Image native runtime is incompatible.", error)
            }
        }
    }

    override suspend fun generate(prompt: String, image: ByteArray?): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            require(prompt.isNotBlank() && prompt.length <= 8000)
            require(image == null || image.size in 1..2_000_000)
            val ready = checkNotNull(engine)
            try {
                ready.createConversation(ConversationConfig(
                    automaticToolCalling = false,
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                )).use { conversation ->
                    val contents = if (image == null) Contents.of(Content.Text(prompt))
                        else Contents.of(Content.ImageBytes(image), Content.Text(prompt))
                    val text = modelResponseText(conversation.sendMessage(contents))
                    currentCoroutineContext().ensureActive()
                    text
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                failed = true
                throw LocalInferenceException("Image inference failed.", error)
            } catch (error: LinkageError) {
                failed = true
                throw LocalInferenceException("Image native inference failed.", error)
            }
        }
    }

    override suspend fun unload(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val previous = engine
            engine = null
            closeEngine(previous)
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            val previous = engine
            engine = null
            closeEngine(previous)
        }
    }

    private fun closeEngine(previous: Engine?) {
        try { previous?.close()
        } catch (error: Exception) { throw LocalInferenceException("Image engine cleanup failed.", error)
        } catch (error: LinkageError) { throw LocalInferenceException("Image native cleanup failed.", error) }
    }
}
