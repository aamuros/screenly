package com.screenly.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
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

/**
 * Isolated text inference; no accessibility or planner dependency.
 *
 * The owner must call [close] in a finally block. Native synchronous calls finish on IO before
 * cancellation releases their resources; cancellation does not interrupt an in-flight JNI call.
 */
internal class LocalInference(
    private val privateDirectory: File,
    private val cacheDirectory: File
) {
    constructor(context: Context) : this(
        context.applicationContext.noBackupFilesDir,
        File(context.applicationContext.cacheDir, "local-ai")
    )

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var closed = false

    /** Verifies and loads the model once. Repeated calls reuse the initialized engine. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Local inference is closed." }
            if (engine != null) return@withLock
            try {
                val model = LocalModel.fileIn(privateDirectory)
                verifyModelFile(model)
                currentCoroutineContext().ensureActive()
                if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
                    throw IOException("Cannot create local inference cache: ${cacheDirectory.absolutePath}")
                }
                val candidate = Engine(
                    EngineConfig(
                        modelPath = model.absolutePath,
                        backend = Backend.CPU(numOfThreads = 4),
                        maxNumTokens = 4096,
                        cacheDir = cacheDirectory.absolutePath
                    )
                )
                // Engine.close() is invalid before successful initialize() in this API version.
                candidate.initialize()
                engine = candidate
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw LocalInferenceException("Local model initialization failed: ${error.message}", error)
            } catch (error: LinkageError) {
                throw LocalInferenceException("LiteRT-LM native runtime could not load on this device.", error)
            }
        }
    }

    /** Each prompt gets a fresh conversation, while retaining the loaded engine. */
    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Local inference is closed." }
            require(prompt.isNotBlank()) { "Prompt must not be blank." }
            require(prompt.length <= 8000) { "Prompts must be at most 8000 characters." }
            val readyEngine = checkNotNull(engine) { "Initialize the local model before generating text." }
            try {
                readyEngine.createConversation(ConversationConfig(automaticToolCalling = false)).use { conversation ->
                    val response = modelResponseText(conversation.sendMessage(prompt))
                    currentCoroutineContext().ensureActive()
                    response
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw LocalInferenceException("Local text generation failed: ${error.message}", error)
            } catch (error: LinkageError) {
                throw LocalInferenceException("LiteRT-LM native text generation failed.", error)
            }
        }
    }

    /** Idempotent, serialized cleanup also runs when the owner's coroutine is cancelled. */
    suspend fun close(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            val previous = engine
            engine = null
            try {
                previous?.close()
            } catch (error: Exception) {
                throw LocalInferenceException("Local engine cleanup failed: ${error.message}", error)
            } catch (error: LinkageError) {
                throw LocalInferenceException("LiteRT-LM native cleanup failed.", error)
            }
        }
    }
}

internal class LocalInferenceException(message: String, cause: Throwable) : IOException(message, cause)

/** Extracts only answer text through the pinned Kotlin API, excluding non-text data and channels. */
internal fun modelResponseText(message: Message): String {
    val text = message.contents.contents
        .mapNotNull { (it as? Content.Text)?.text }
        .joinToString("")
    if (text.isBlank()) throw IOException("Local model returned no nonblank text response.")
    return text
}
