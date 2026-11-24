package com.myid.wallet.util

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

data class ExtractedDocumentMetadata(
    val documentNumber: String? = null,
    val holderName: String? = null,
    val dateOfBirth: String? = null,
    val expiryDate: String? = null
)

object OcrExtractor {
    suspend fun extract(bitmap: Bitmap): ExtractedDocumentMetadata =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val text = result.text
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val dob = regexFind(lines, Regex("""(?:DOB|DOB:|Date of Birth|Birth)[\s:]*([0-3]?\d[/-][0-1]?\d[/-](?:19|20)\d{2})""", RegexOption.IGNORE_CASE))
                        ?: regexFind(lines, Regex("""[0-3]?\d[/-][0-1]?\d[/-](?:19|20)\d{2}"""))
                    val expiry = regexFind(lines, Regex("""(?:EXP|EXPIRY|VALID TO|VALID UPTO)[\s:]*([0-3]?\d[/-][0-1]?\d[/-](?:20|19)\d{2})""", RegexOption.IGNORE_CASE))
                    val number = lines.firstNotNullOfOrNull { line ->
                        Regex("""(?<!\d)([A-Z]{2,5}[\s-]?\d{4,12})(?!\d)""").find(line)?.groupValues?.get(1)
                            ?: Regex("""(?<!\d)(\d{4}[\s-]?\d{4}[\s-]?\d{4})(?!\d)""").find(line)?.groupValues?.get(1)
                    }?.replace(Regex("\\s+"), "")
                    val name = inferName(lines)
                    recognizer.close()
                    continuation.resume(ExtractedDocumentMetadata(maskAadhaar(number), name, dob, expiry))
                }
                .addOnFailureListener {
                    recognizer.close()
                    continuation.resume(ExtractedDocumentMetadata())
                }
        }

    private fun regexFind(lines: List<String>, regex: Regex): String? =
        lines.firstNotNullOfOrNull { regex.find(it)?.groupValues?.getOrNull(1) }

    private fun inferName(lines: List<String>): String? {
        val nameIndex = lines.indexOfFirst {
            it.contains("name", ignoreCase = true) && !it.contains("surname", ignoreCase = true)
        }
        if (nameIndex >= 0) {
            val sameLine = lines[nameIndex].substringAfter(":", "").trim()
            if (sameLine.length >= 3) return sameLine
            if (nameIndex + 1 < lines.size) return lines[nameIndex + 1].trim()
        }
        return null
    }

    private fun maskAadhaar(value: String?): String? {
        val digits = value?.filter(Char::isDigit) ?: return value
        return if (digits.length == 12) "XXXX-XXXX-${digits.takeLast(4)}" else value
    }
}
