package com.dangtuan.btranslate.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TranslatedParagraph(
    val text: String,
    val box: Rect,
    val backgroundColor: Int,
    val textColor: Int,
    val textSizePx: Float
)

class TranslationEngine {
    suspend fun translate(
        bitmap: Bitmap,
        source: LanguageOption,
        target: LanguageOption
    ): List<TranslatedParagraph> {
        if (source.code == target.code) return emptyList()

        val recognizer = recognizerFor(source.ocr)
        val recognizedText = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } finally {
            recognizer.close()
        }

        val paragraphCandidates = recognizedText.textBlocks.flatMap { block ->
            splitIntoParagraphs(block.lines.mapNotNull(::lineInfo))
        }
        if (paragraphCandidates.isEmpty()) return emptyList()

        val identifier = LanguageIdentification.getClient()
        val plans = try {
            paragraphCandidates.mapNotNull { candidate ->
                planParagraph(candidate, source.code, target.code, identifier)
            }
        } finally {
            identifier.close()
        }
        if (plans.isEmpty()) return emptyList()

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source.code)
                .setTargetLanguage(target.code)
                .build()
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            plans.map { plan -> renderPlan(bitmap, plan, translator) }
        } finally {
            translator.close()
        }
    }

    private fun lineInfo(line: Text.Line): LineInfo? {
        val box = line.boundingBox ?: return null
        if (line.text.isBlank()) return null
        return LineInfo(
            text = line.text.trim(),
            box = Rect(box),
            elements = line.elements.mapNotNull { element ->
                element.boundingBox?.let { ElementPart(element.text, Rect(it)) }
            }
        )
    }

    /**
     * Không giới hạn số dòng. Các dòng tiếp tục thuộc cùng một đoạn khi chúng
     * cùng cột, có chiều cao gần nhau và khe dọc vẫn gần với nhịp của đoạn.
     */
    private fun splitIntoParagraphs(lines: List<LineInfo>): List<ParagraphCandidate> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<LineInfo> { it.box.top }.thenBy { it.box.left })
        val result = mutableListOf<ParagraphCandidate>()
        var current = mutableListOf(sorted.first())
        val acceptedGaps = mutableListOf<Int>()

        fun flush() {
            if (current.isNotEmpty()) result += ParagraphCandidate(current.toList())
            current = mutableListOf()
            acceptedGaps.clear()
        }

        for (next in sorted.drop(1)) {
            val previous = current.last()
            val gap = next.box.top - previous.box.bottom
            if (continuesParagraph(current, acceptedGaps, next, gap)) {
                current += next
                acceptedGaps += max(0, gap)
            } else {
                flush()
                current += next
            }
        }
        flush()
        return result
    }

    private fun continuesParagraph(
        current: List<LineInfo>,
        acceptedGaps: List<Int>,
        next: LineInfo,
        gap: Int
    ): Boolean {
        val previous = current.last()
        val typicalHeight = median((current.map { it.box.height() } + next.box.height()).filter { it > 0 })
        val heightRatio = max(previous.box.height(), next.box.height()).toFloat() /
            max(1, min(previous.box.height(), next.box.height())).toFloat()
        if (heightRatio > MAX_LINE_HEIGHT_RATIO) return false
        if (gap < -typicalHeight * 0.35f) return false

        val overlap = min(previous.box.right, next.box.right) - max(previous.box.left, next.box.left)
        val minimumWidth = max(1, min(previous.box.width(), next.box.width()))
        val leftDifference = abs(previous.box.left - next.box.left)
        val sameColumn = overlap >= minimumWidth * MIN_HORIZONTAL_OVERLAP ||
            leftDifference <= max(typicalHeight * MAX_LEFT_SHIFT_IN_HEIGHTS, MIN_LEFT_SHIFT_PX.toFloat())
        if (!sameColumn) return false

        if (acceptedGaps.isEmpty()) {
            return gap <= typicalHeight * FIRST_GAP_IN_HEIGHTS
        }

        val expectedGap = acceptedGaps.average().toFloat()
        val tolerance = max(typicalHeight * GAP_TOLERANCE_IN_HEIGHTS, expectedGap * 0.75f + 2f)
        return gap <= expectedGap + tolerance
    }

    private suspend fun planParagraph(
        candidate: ParagraphCandidate,
        sourceCode: String,
        targetCode: String,
        identifier: LanguageIdentifier
    ): ParagraphPlan? {
        val parts = candidate.lines.flatMap { line ->
            if (line.elements.isEmpty()) listOf(ElementPart(line.text, line.box)) else line.elements
        }
        val roles = parts.map { classify(it.text, sourceCode, targetCode, identifier) }
        val sourceCount = roles.count { it == LanguageRole.SOURCE }
        val targetCount = roles.count { it == LanguageRole.TARGET }
        val otherCount = roles.count { it == LanguageRole.OTHER }
        if (sourceCount == 0 || sourceCount < max(targetCount, otherCount)) return null

        val text = candidate.lines.joinToString(" ") { it.text }.replace(WHITESPACE, " ").trim()
        if (text.isBlank()) return null
        return ParagraphPlan(
            text = text,
            box = union(candidate.lines.map { it.box }),
            firstLineBox = Rect(candidate.lines.first().box),
            typicalLineHeight = median(candidate.lines.map { it.box.height() }.filter { it > 0 })
        )
    }

    private suspend fun renderPlan(
        bitmap: Bitmap,
        plan: ParagraphPlan,
        translator: Translator
    ): TranslatedParagraph {
        val translatedText = translator.translate(plan.text).await().replace(WHITESPACE, " ").trim()
        val backgroundColor = sampleOneBackgroundPoint(bitmap, plan.box, plan.firstLineBox)
        return TranslatedParagraph(
            text = translatedText,
            box = Rect(plan.box),
            backgroundColor = backgroundColor,
            textColor = contrastingTextColor(backgroundColor),
            textSizePx = max(MIN_TEXT_SIZE_PX, plan.typicalLineHeight * TEXT_SIZE_FROM_LINE_HEIGHT)
        )
    }

    /**
     * Mỗi đoạn chỉ lấy đúng một pixel gần đầu đoạn rồi dùng màu đó cho toàn bảng.
     * Ưu tiên điểm ngay trước dòng đầu; nếu sát mép màn hình thì lấy phía trên.
     */
    private fun sampleOneBackgroundPoint(bitmap: Bitmap, paragraph: Rect, firstLine: Rect): Int {
        val distance = max(MIN_SAMPLE_DISTANCE_PX, firstLine.height() / 3)
        val candidates = listOf(
            (firstLine.left - distance) to firstLine.centerY(),
            (firstLine.left + min(distance, max(0, firstLine.width() - 1))) to (firstLine.top - distance),
            (paragraph.right + distance) to firstLine.centerY(),
            paragraph.left to (paragraph.bottom + distance)
        )
        val point = candidates.firstOrNull { (x, y) ->
            x in 0 until bitmap.width && y in 0 until bitmap.height
        } ?: (
            paragraph.left.coerceIn(0, bitmap.width - 1) to
                paragraph.top.coerceIn(0, bitmap.height - 1)
            )
        return bitmap.getPixel(point.first, point.second)
    }

    private fun contrastingTextColor(background: Int): Int {
        val luminance =
            0.2126 * Color.red(background) +
                0.7152 * Color.green(background) +
                0.0722 * Color.blue(background)
        return if (luminance > 145.0) Color.BLACK else Color.WHITE
    }

    private suspend fun classify(
        value: String,
        sourceCode: String,
        targetCode: String,
        identifier: LanguageIdentifier
    ): LanguageRole {
        if (value.none(Char::isLetter)) return LanguageRole.NEUTRAL

        detectDistinctScript(value)?.let { detected ->
            return when (detected) {
                sourceCode -> LanguageRole.SOURCE
                targetCode -> LanguageRole.TARGET
                else -> LanguageRole.OTHER
            }
        }

        val candidates = identifier.identifyPossibleLanguages(value).await()
        val confidence = candidates.associate { normalizeLanguageCode(it.languageTag) to it.confidence }
        val sourceConfidence = confidence[sourceCode] ?: 0f
        val targetConfidence = confidence[targetCode] ?: 0f
        val strongestOther = confidence
            .filterKeys { it != sourceCode && it != targetCode && it != "und" }
            .values.maxOrNull() ?: 0f

        if (sourceConfidence >= LANGUAGE_CONFIDENCE &&
            sourceConfidence >= max(targetConfidence, strongestOther)
        ) {
            return LanguageRole.SOURCE
        }
        if (targetConfidence >= LANGUAGE_CONFIDENCE &&
            targetConfidence >= max(sourceConfidence, strongestOther)
        ) {
            return LanguageRole.TARGET
        }
        if (strongestOther >= LANGUAGE_CONFIDENCE) return LanguageRole.OTHER

        if (looksLikeUndeterminedEnglish(value)) {
            return when ("en") {
                sourceCode -> LanguageRole.SOURCE
                targetCode -> LanguageRole.TARGET
                else -> LanguageRole.OTHER
            }
        }
        return LanguageRole.OTHER
    }

    private fun union(boxes: List<Rect>): Rect = Rect(boxes.first()).also { result ->
        boxes.drop(1).forEach(result::union)
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 1
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun looksLikeUndeterminedEnglish(value: String): Boolean =
        value.any { it in 'A'..'Z' || it in 'a'..'z' } && value.none(::isVietnameseSpecificLetter)

    private fun detectDistinctScript(value: String): String? = when {
        value.any { it in '\uAC00'..'\uD7AF' } -> "ko"
        value.any { it in '\u3040'..'\u30FF' } -> "ja"
        value.any { it in '\u3400'..'\u9FFF' } -> "zh"
        value.any(::isVietnameseSpecificLetter) -> "vi"
        else -> null
    }

    private fun isVietnameseSpecificLetter(char: Char): Boolean =
        char.lowercaseChar() in VIETNAMESE_SPECIFIC_LETTERS

    private fun normalizeLanguageCode(tag: String): String =
        tag.substringBefore('-').lowercase()

    private fun recognizerFor(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private data class ElementPart(val text: String, val box: Rect)
    private data class LineInfo(val text: String, val box: Rect, val elements: List<ElementPart>)
    private data class ParagraphCandidate(val lines: List<LineInfo>)
    private data class ParagraphPlan(
        val text: String,
        val box: Rect,
        val firstLineBox: Rect,
        val typicalLineHeight: Int
    )
    private enum class LanguageRole { SOURCE, TARGET, OTHER, NEUTRAL }

    private companion object {
        const val LANGUAGE_CONFIDENCE = 0.20f
        const val MAX_LINE_HEIGHT_RATIO = 1.65f
        const val MIN_HORIZONTAL_OVERLAP = 0.15f
        const val MAX_LEFT_SHIFT_IN_HEIGHTS = 1.5f
        const val MIN_LEFT_SHIFT_PX = 24
        const val FIRST_GAP_IN_HEIGHTS = 1.25f
        const val GAP_TOLERANCE_IN_HEIGHTS = 0.65f
        const val MIN_SAMPLE_DISTANCE_PX = 3
        const val MIN_TEXT_SIZE_PX = 8f
        const val TEXT_SIZE_FROM_LINE_HEIGHT = 0.72f
        val WHITESPACE = Regex("\\s+")
        val VIETNAMESE_SPECIFIC_LETTERS =
            "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ".toSet()
    }
}
