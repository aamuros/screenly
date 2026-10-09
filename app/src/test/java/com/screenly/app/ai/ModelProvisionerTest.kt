package com.screenly.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelProvisionerTest {
    @Test fun corruptOrCancelledImportsPreserveExistingModelAndRemoveStaging() = runBlocking {
        val directory = Files.createTempDirectory("screenly-model").toFile()
        val model = File(directory, "model")
        model.writeText("working")
        val bytes = "new".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        try {
            for (cancel in listOf(false, true)) {
                try {
                    stageModel(ByteArrayInputStream(bytes), model, bytes.size.toLong(), if (cancel) hash else "0".repeat(64)) {
                        if (cancel) throw CancellationException()
                    }
                    fail("Invalid import must fail")
                } catch (_: Exception) { }
                assertEquals("working", model.readText())
                assertEquals(listOf("model"), directory.listFiles()!!.map { it.name })
            }
            stageModel(ByteArrayInputStream(bytes), model, bytes.size.toLong(), hash)
            assertEquals("new", model.readText())
        } finally { model.delete(); directory.delete() }
    }
}
