package com.screenly.app.ai.navigation

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.screenly.app.ai.LocalInference
import com.screenly.app.ai.LocalInferenceException
import com.screenly.app.ai.modelResponseText
import com.screenly.app.ai.verifyModelFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Instrumentation-only alternatives; never replaces LocalModel or a live guidance consumer. */
internal class ComparisonInference(context: Context, val modelId: String) {
    private val baseline = if (modelId == "gemma") LocalInference(context) else null
    private val modelDirectory = File(context.noBackupFilesDir, "experimental-models")
    private val cacheDirectory = File(context.cacheDir, "m4-comparison/$modelId")
    private var engine: Engine? = null

    init {
        require(modelId in setOf("gemma", "qwen3-nothink-int4", "qwen25-instruct-int8"))
    }

    suspend fun initialize() {
        baseline?.let { it.initialize(); return }
        withContext(Dispatchers.IO) {
            try {
                val model = File(modelDirectory, "$modelId.litertlm")
                val (size, hash) = when (modelId) {
                    "qwen3-nothink-int4" -> 347_251_840L to
                        "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
                    else -> 1_597_931_520L to
                        "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"
                }
                verifyModelFile(model, size, hash)
                check(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
                val candidate = Engine(EngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(numOfThreads = 4),
                    maxNumTokens = 1024,
                    cacheDir = cacheDirectory.absolutePath
                ))
                candidate.initialize()
                engine = candidate
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw LocalInferenceException("Experimental initialization failed: ${error.message}", error)
            } catch (error: LinkageError) {
                throw LocalInferenceException("Experimental runtime linkage failed.", error)
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun generate(prompt: String, inspect: (Message, BenchmarkInfo) -> Unit): String {
        baseline?.let { return it.generate(prompt, inspect) }
        return withContext(Dispatchers.IO) {
            require(prompt.isNotBlank() && prompt.length <= 1000)
            try {
                checkNotNull(engine).createConversation(
                    ConversationConfig(automaticToolCalling = false)
                ).use { conversation ->
                    val message = conversation.sendMessage(prompt)
                    inspect(message, conversation.getBenchmarkInfo())
                    modelResponseText(message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw LocalInferenceException("Experimental generation failed: ${error.message}", error)
            } catch (error: LinkageError) {
                throw LocalInferenceException("Experimental generation linkage failed.", error)
            }
        }
    }

    suspend fun close() {
        baseline?.let { it.close(); return }
        withContext(NonCancellable + Dispatchers.IO) {
            val previous = engine
            engine = null
            previous?.close()
        }
    }
}
