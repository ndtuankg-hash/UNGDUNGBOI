package com.dangtuan.btranslate.translation

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class TranslatedLine(val text: String, val box: Rect)

class TranslationEngine {
    suspend fun translate(bitmap: Bitmap, source: LanguageOption, target: LanguageOption): List<TranslatedLine> {
        val recognizer = recognizerFor(source.ocr)
        val recognizedText = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } finally {
            recognizer.close()
        }
        val lines = recognizedText.textBlocks.flatMap { it.lines }
        if (source.code == target.code) {
            return lines.mapNotNull { line -> line.boundingBox?.let { TranslatedLine(line.text, Rect(it)) } }
        }

        val languageIdentifier = LanguageIdentification.getClient()
        val sourceParts = try {
            buildList {
                for (line in lines) {
                    val lineBox = line.boundingBox ?: continue
                    val elements = line.elements.mapNotNull { element ->
                        element.boundingBox?.let { TextPart(element.text, Rect(it)) }
                    }
                    if (elements.isEmpty()) {
                        if (isSourceText(line.text, source.code, languageIdentifier)) {
                            add(TextPart(line.text, Rect(lineBox)))
                        }
                        continue
                    }

                    val selected = elements.map { part ->
                        part to isSourceText(part.text, source.code, languageIdentifier)
                    }
                    when {
                        selected.all { it.second } -> add(TextPart(line.text, Rect(lineBox)))
                        else -> selected.filter { it.second }.forEach { add(it.first) }
                    }
                }
            }
        } finally {
            languageIdentifier.close()
        }

        if (sourceParts.isEmpty()) return emptyList()

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source.code)
                .setTargetLanguage(target.code)
                .build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            sourceParts.map { part ->
                TranslatedLine(translator.translate(part.text).await(), part.box)
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun isSourceText(
        value: String,
        sourceCode: String,
        languageIdentifier: com.google.mlkit.nl.languageid.LanguageIdentifier
    ): Boolean {
        if (value.none(Char::isLetter)) return false

        detectDistinctScript(value)?.let { detected ->
            return detected == sourceCode
        }

        val candidates = languageIdentifier.identifyPossibleLanguages(value).await()
        val sourceConfidence = candidates
            .filter { normalizeLanguageCode(it.languageTag) == sourceCode }
            .maxOfOrNull { it.confidence } ?: 0f
        val strongestOther = candidates
            .filter { normalizeLanguageCode(it.languageTag) != sourceCode }
            .maxOfOrNull { it.confidence } ?: 0f

        if (sourceConfidence >= LANGUAGE_CONFIDENCE && sourceConfidence >= strongestOther) return true

        // Các từ giao diện tiếng Anh ngắn thường bị trả về "und". Chỉ dùng dự phòng
        // này cho chữ ASCII, nên những từ tiếng Việt có dấu vẫn luôn được bỏ qua.
        return sourceCode == "en" && sourceConfidence > 0f && value.any { it in 'A'..'Z' || it in 'a'..'z' } &&
            value.none(::isVietnameseSpecificLetter)
    }

    private fun detectDistinctScript(value: String): String? = when {
        value.any { it in '\uAC00'..'\uD7AF' } -> "ko"
        value.any { it in '\u3040'..'\u30FF' } -> "ja"
        value.any { it in '\u3400'..'\u9FFF' } -> "zh"
        value.any(::isVietnameseSpecificLetter) -> "vi"
        else -> null
    }

    private fun isVietnameseSpecificLetter(char: Char): Boolean = char.lowercaseChar() in VIETNAMESE_SPECIFIC_LETTERS

    private fun normalizeLanguageCode(tag: String): String = tag.substringBefore('-').lowercase()

    private data class TextPart(val text: String, val box: Rect)

    private companion object {
        const val LANGUAGE_CONFIDENCE = 0.20f
        val VIETNAMESE_SPECIFIC_LETTERS =
            "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ".toSet()
    }

    private fun recognizerFor(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
