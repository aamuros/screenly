package com.screenly.app.ai

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Exact INT4 candidate published by litert-community/Gemma3-1B-IT; phone validation is pending. */
internal object LocalModel {
    const val FILE_NAME = "gemma3-1b-it-int4.litertlm"
    const val SIZE_BYTES = 584_417_280L
    const val SHA256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be"

    fun fileIn(privateDirectory: File): File = File(privateDirectory, "models/$FILE_NAME")
}

/** Rejects missing, partial and substituted downloads before entering the native runtime. */
internal fun verifyModelFile(
    file: File,
    expectedSize: Long = LocalModel.SIZE_BYTES,
    expectedSha256: String = LocalModel.SHA256
) {
    if (!file.isFile || !file.canRead()) {
        throw IOException("Local model is missing or unreadable: ${file.absolutePath}. Provision it using ADB.")
    }
    if (file.length() != expectedSize) {
        throw IOException("Local model size mismatch: expected $expectedSize bytes, found ${file.length()}.")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (actual != expectedSha256) {
        throw IOException("Local model SHA-256 mismatch. Reprovision the documented artifact.")
    }
}
