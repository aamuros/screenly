package com.screenly.app.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local SAF import only. Copy/verify a staging file before atomically replacing any working model. */
internal class ModelProvisioner(private val context: Context) {
    private val mutex = Mutex()
    suspend fun import(uri: Uri, vision: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = context.applicationContext.noBackupFilesDir
            val destination = if (vision) VisionModel.fileIn(directory) else LocalModel.fileIn(directory)
            val size = if (vision) VisionModel.SIZE_BYTES else LocalModel.SIZE_BYTES
            val hash = if (vision) VisionModel.SHA256 else LocalModel.SHA256
            context.contentResolver.openInputStream(uri)?.use { input ->
                stageModel(input, destination, size, hash) { currentCoroutineContext().ensureActive() }
            } ?: throw IOException("Cannot open selected model.")
            if (vision) VisionModel.certificationIn(directory).delete()
        }
    }
}

internal suspend fun stageModel(input: InputStream, destination: File, size: Long, hash: String, checkActive: suspend () -> Unit = {}) {
    val directory = checkNotNull(destination.parentFile)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create private model directory.")
    val staged = File.createTempFile("model-", ".partial", directory)
    try {
        staged.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > size) throw IOException("Selected model exceeds expected size.")
                output.write(buffer, 0, count)
            }
        }
        checkActive()
        verifyModelFile(staged, size, hash)
        checkActive()
        Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { staged.delete() }
}
