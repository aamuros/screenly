package com.screenly.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Text-only, on-device OCR. The caller owns and recycles the supplied bitmap. */
internal data class OcrTextLine(
    val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int
)

internal class OfflineOcr : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun recognize(bitmap: Bitmap, done: (Result<List<OcrTextLine>>) -> Unit) {
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val rect = line.boundingBox ?: return@mapNotNull null
                        val text = sanitizeObservationText(line.text) ?: return@mapNotNull null
                        OcrTextLine(text, rect.left, rect.top, rect.right, rect.bottom)
                    }.take(40)
                    done(Result.success(lines))
                }
                .addOnFailureListener { failure -> done(Result.failure(failure)) }
        } catch (failure: RuntimeException) {
            done(Result.failure(failure))
        }
    }

    override fun close() {
        recognizer.close()
    }
}

/** OCR is evidence of visible text, never proof that a text region is clickable. */
internal object OcrEvidence {
    fun summary(lines: List<OcrTextLine>): String {
        if (lines.isEmpty()) return "OCR found no readable text."
        return "Offline OCR text (not verified tap targets):\n" +
            lines.take(12).joinToString("\n") { "${it.text} [${it.left},${it.top}]" }
    }
}
