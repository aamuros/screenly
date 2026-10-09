package com.screenly.app.ai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalInferenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sampleSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun responseContainsOnlyOrderedTextParts() {
        val message = Message.model(
            contents = Contents.of(Content.Text("Font "), Content.ImageFile("/unused/image"), Content.Text("size")),
            channels = mapOf("thought" to "Excluded channel content")
        )
        assertEquals("Font size", modelResponseText(message))
    }

    @Test
    fun noneIsLiteralModelTextRatherThanAnEmptyResponsePlaceholder() {
        assertEquals("None", modelResponseText(Message.model(Contents.of("None"))))
    }

    @Test
    fun nonTextResponseDoesNotPassAsGeneratedText() {
        val message = Message.model(Contents.of(Content.ImageFile("/unused/image")))
        assertThrows(IOException::class.java) { modelResponseText(message) }
    }

    @Test
    fun emptyAndWhitespaceOnlyResponsesAreRejected() {
        listOf(Message.model(), Message.model(Contents.of(" \n\t"))).forEach { message ->
            assertThrows(IOException::class.java) { modelResponseText(message) }
        }
    }

    @Test
    fun validFilePassesIntegrityVerification() {
        verifyModelFile(sampleModel("abc"), 3, sampleSha256)
    }

    @Test
    fun missingModelReportsItsPathAndProvisioningAction() {
        val file = File(temporaryFolder.root, LocalModel.FILE_NAME)
        val error = assertThrows(IOException::class.java) { verifyModelFile(file) }
        assertTrue(error.message!!.contains(file.absolutePath))
        assertTrue(error.message!!.contains("ADB"))
    }

    @Test
    fun truncatedDownloadIsRejected() {
        val error = assertThrows(IOException::class.java) {
            verifyModelFile(sampleModel("ab"), 3, sampleSha256)
        }
        assertTrue(error.message!!.contains("size mismatch"))
    }

    @Test
    fun sameLengthCorruptionIsRejected() {
        val error = assertThrows(IOException::class.java) {
            verifyModelFile(sampleModel("abd"), 3, sampleSha256)
        }
        assertTrue(error.message!!.contains("SHA-256 mismatch"))
    }

    @Test
    fun unavailableModelCanBeRetriedAndCleanupIsIdempotent() = runBlocking<Unit> {
        val inference = LocalInference(temporaryFolder.root, File(temporaryFolder.root, "cache"))
        try {
            assertThrows(LocalInferenceException::class.java) { runBlocking { inference.initialize() } }
            val model = LocalModel.fileIn(temporaryFolder.root)
            assertTrue(model.parentFile!!.mkdirs())
            model.writeText("partial download")
            val retry = assertThrows(LocalInferenceException::class.java) { runBlocking { inference.initialize() } }
            assertTrue(retry.message!!.contains("size mismatch"))
        } finally {
            inference.close()
            inference.close()
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { inference.initialize() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { inference.generate("Hello") } }
    }

    @Test
    fun generationBeforeInitializationFailsClearly() = runBlocking<Unit> {
        val inference = LocalInference(temporaryFolder.root, File(temporaryFolder.root, "cache"))
        try {
            val error = assertThrows(IllegalStateException::class.java) { runBlocking { inference.generate("Hello") } }
            assertEquals("Initialize the local model before generating text.", error.message)
        } finally {
            inference.close()
        }
    }

    private fun sampleModel(text: String): File = temporaryFolder.newFile(LocalModel.FILE_NAME).apply {
        writeText(text)
    }
}
