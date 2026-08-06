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
import kotlin.math.max
import kotlin.math.min

data class TranslatedLine(
    val text: String,
    val box: Rect,
    val backgroundBox: Rect,
    val background: Bitmap,
    val textColor: Int
)

class TranslationEngine {
    suspend fun translate(bitmap: Bitmap, source: LanguageOption, target: LanguageOption): List<TranslatedLine> {
        if (source.code == target.code) return emptyList()

        val recognizer = recognizerFor(source.ocr)
        val recognizedText = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } finally {
            recognizer.close()
        }

        val languageIdentifier = LanguageIdentification.getClient()
        val plans = try {
            recognizedText.textBlocks
                .flatMap { it.lines }
                .mapNotNull { planLine(it, source.code, target.code, languageIdentifier) }
        } finally {
            languageIdentifier.close()
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

    private suspend fun planLine(
        line: Text.Line,
        sourceCode: String,
        targetCode: String,
        identifier: LanguageIdentifier
    ): LinePlan? {
        val lineBox = line.boundingBox ?: return null
        val elements = line.elements.mapNotNull { element ->
            element.boundingBox?.let { ElementPart(element.text, Rect(it)) }
        }
        if (elements.isEmpty()) {
            return when (classify(line.text, sourceCode, targetCode, identifier)) {
                LanguageRole.SOURCE -> LinePlan(Rect(lineBox), listOf(Segment(line.text, Rect(lineBox), true)))
                else -> null
            }
        }

        val classified = elements.map { part ->
            ClassifiedPart(part, classify(part.text, sourceCode, targetCode, identifier))
        }
        val meaningful = classified.filter { it.role != LanguageRole.NEUTRAL }
        if (meaningful.isEmpty()) return null

        // Số, ký hiệu và dấu câu là trung tính. Chúng không được phép làm một dòng
        // thuần ngôn ngữ nguồn rơi xuống chế độ dịch từng từ như ở bản 0.1.5.
        if (meaningful.all { it.role == LanguageRole.SOURCE }) {
            return LinePlan(Rect(lineBox), listOf(Segment(line.text, Rect(lineBox), true)))
        }
        if (meaningful.none { it.role == LanguageRole.SOURCE }) return null

        // Dòng thật sự trộn ngôn ngữ: chỉ dịch các chuỗi thuộc ngôn ngữ nguồn,
        // giữ phần còn lại rồi ghép thành đúng một kết quả cho cả dòng.
        val segments = mutableListOf<Segment>()
        var index = 0
        while (index < classified.size) {
            val current = classified[index]
            if (current.role != LanguageRole.SOURCE) {
                segments += Segment(current.part.text, current.part.box, false)
                index++
                continue
            }

            val run = mutableListOf(current.part)
            var end = index + 1
            while (end < classified.size) {
                val candidate = classified[end]
                if (candidate.role == LanguageRole.SOURCE) {
                    run += candidate.part
                    end++
                    continue
                }
                if (candidate.role == LanguageRole.NEUTRAL &&
                    classified.drop(end + 1).firstOrNull { it.role != LanguageRole.NEUTRAL }?.role == LanguageRole.SOURCE
                ) {
                    run += candidate.part
                    end++
                    continue
                }
                break
            }
            segments += Segment(joinParts(run), union(run.map { it.box }), true)
            index = end
        }
        return LinePlan(Rect(lineBox), segments)
    }

    private suspend fun renderPlan(bitmap: Bitmap, plan: LinePlan, translator: Translator): TranslatedLine {
        val renderedSegments = plan.segments.map { segment ->
            segment.copy(text = if (segment.translate) translator.translate(segment.text).await() else segment.text)
        }
        val translatedText = joinSegments(renderedSegments)
        val backgroundBox = expandedBox(plan.box, bitmap.width, bitmap.height)
        val background = inpaintLineBackground(bitmap, plan.box, backgroundBox)
        return TranslatedLine(
            text = translatedText,
            box = Rect(plan.box),
            backgroundBox = backgroundBox,
            background = background,
            textColor = estimateTextColor(bitmap, plan.box, backgroundBox, background)
        )
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

        if (sourceConfidence >= LANGUAGE_CONFIDENCE && sourceConfidence >= max(targetConfidence, strongestOther)) {
            return LanguageRole.SOURCE
        }
        if (targetConfidence >= LANGUAGE_CONFIDENCE && targetConfidence >= max(sourceConfidence, strongestOther)) {
            return LanguageRole.TARGET
        }
        if (strongestOther >= LANGUAGE_CONFIDENCE) return LanguageRole.OTHER

        // ML Kit thường trả "und" cho nhãn giao diện tiếng Anh rất ngắn.
        if (looksLikeUndeterminedEnglish(value)) {
            return when ("en") {
                sourceCode -> LanguageRole.SOURCE
                targetCode -> LanguageRole.TARGET
                else -> LanguageRole.OTHER
            }
        }
        return LanguageRole.OTHER
    }

    private fun joinParts(parts: List<ElementPart>): String = parts.joinToString(" ") { it.text }

    private fun joinSegments(segments: List<Segment>): String = buildString {
        segments.forEachIndexed { index, segment ->
            if (index > 0 && needsSpace(segments[index - 1].text, segment.text)) append(' ')
            append(segment.text.trim())
        }
    }.trim()

    private fun needsSpace(previous: String, current: String): Boolean {
        val left = previous.trim().lastOrNull() ?: return false
        val right = current.trim().firstOrNull() ?: return false
        if (right in CLOSING_PUNCTUATION || left in OPENING_PUNCTUATION) return false
        if (right == '\'' || right == '’') return false
        return true
    }

    private fun union(boxes: List<Rect>): Rect = Rect(boxes.first()).also { result ->
        boxes.drop(1).forEach(result::union)
    }

    private fun expandedBox(box: Rect, width: Int, height: Int): Rect {
        val horizontal = max(2, box.height() / 12)
        val vertical = max(2, box.height() / 8)
        return Rect(
            max(0, box.left - horizontal),
            max(0, box.top - vertical),
            min(width, box.right + horizontal),
            min(height, box.bottom + vertical)
        )
    }

    private fun inpaintLineBackground(source: Bitmap, textBox: Rect, outputBox: Rect): Bitmap {
        val width = outputBox.width().coerceAtLeast(1)
        val height = outputBox.height().coerceAtLeast(1)
        val pixels = IntArray(width * height)
        val topY = max(0, outputBox.top - 1)
        val bottomY = min(source.height - 1, outputBox.bottom)

        for (localX in 0 until width) {
            val sourceX = (outputBox.left + localX).coerceIn(0, source.width - 1)
            val topColor = sampleBand(source, sourceX, topY)
            val bottomColor = sampleBand(source, sourceX, bottomY)
            for (localY in 0 until height) {
                val globalY = outputBox.top + localY
                pixels[localY * width + localX] = if (globalY < textBox.top || globalY >= textBox.bottom) {
                    source.getPixel(sourceX, globalY.coerceIn(0, source.height - 1))
                } else {
                    val amount = (globalY - textBox.top + 1f) / (textBox.height() + 1f)
                    blend(topColor, bottomColor, amount)
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun sampleBand(bitmap: Bitmap, x: Int, y: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (offsetX in -2..2) {
            val sampledX = (x + offsetX).coerceIn(0, bitmap.width - 1)
            val color = bitmap.getPixel(sampledX, y)
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            count++
        }
        return Color.rgb(red / count, green / count, blue / count)
    }

    private fun blend(start: Int, end: Int, amount: Float): Int = Color.rgb(
        (Color.red(start) + (Color.red(end) - Color.red(start)) * amount).toInt(),
        (Color.green(start) + (Color.green(end) - Color.green(start)) * amount).toInt(),
        (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * amount).toInt()
    )

    private fun contrastingTextColor(background: Bitmap): Int {
        var luminance = 0.0
        var samples = 0
        val stepX = max(1, background.width / 12)
        val stepY = max(1, background.height / 6)
        for (y in 0 until background.height step stepY) {
            for (x in 0 until background.width step stepX) {
                val color = background.getPixel(x, y)
                luminance += 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
                samples++
            }
        }
        return if (samples > 0 && luminance / samples > 145.0) Color.BLACK else Color.WHITE
    }

    private fun estimateTextColor(
        source: Bitmap,
        textBox: Rect,
        backgroundBox: Rect,
        reconstructedBackground: Bitmap
    ): Int {
        var weightedRed = 0.0
        var weightedGreen = 0.0
        var weightedBlue = 0.0
        var totalWeight = 0.0
        val clipped = Rect(textBox).apply { intersect(0, 0, source.width, source.height) }

        for (y in clipped.top until clipped.bottom) {
            for (x in clipped.left until clipped.right) {
                val original = source.getPixel(x, y)
                val background = reconstructedBackground.getPixel(
                    (x - backgroundBox.left).coerceIn(0, reconstructedBackground.width - 1),
                    (y - backgroundBox.top).coerceIn(0, reconstructedBackground.height - 1)
                )
                val difference = (
                    kotlin.math.abs(Color.red(original) - Color.red(background)) +
                        kotlin.math.abs(Color.green(original) - Color.green(background)) +
                        kotlin.math.abs(Color.blue(original) - Color.blue(background))
                    ).toDouble()
                if (difference < MIN_GLYPH_DIFFERENCE) continue
                val weight = difference * difference
                weightedRed += Color.red(original) * weight
                weightedGreen += Color.green(original) * weight
                weightedBlue += Color.blue(original) * weight
                totalWeight += weight
            }
        }
        if (totalWeight == 0.0) return contrastingTextColor(reconstructedBackground)

        val estimated = Color.rgb(
            (weightedRed / totalWeight).toInt().coerceIn(0, 255),
            (weightedGreen / totalWeight).toInt().coerceIn(0, 255),
            (weightedBlue / totalWeight).toInt().coerceIn(0, 255)
        )
        val fallback = contrastingTextColor(reconstructedBackground)
        val estimatedLuminance = luminance(estimated)
        val backgroundLuminance = averageLuminance(reconstructedBackground)
        return if (kotlin.math.abs(estimatedLuminance - backgroundLuminance) >= 55.0) estimated else fallback
    }

    private fun averageLuminance(bitmap: Bitmap): Double {
        var total = 0.0
        var count = 0
        val stepX = max(1, bitmap.width / 12)
        val stepY = max(1, bitmap.height / 6)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                total += luminance(bitmap.getPixel(x, y))
                count++
            }
        }
        return if (count == 0) 0.0 else total / count
    }

    private fun luminance(color: Int): Double =
        0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)

    private fun looksLikeUndeterminedEnglish(value: String): Boolean =
        value.any { it in 'A'..'Z' || it in 'a'..'z' } && value.none(::isVietnameseSpecificLetter)

    private fun detectDistinctScript(value: String): String? = when {
        value.any { it in '\uAC00'..'\uD7AF' } -> "ko"
        value.any { it in '\u3040'..'\u30FF' } -> "ja"
        value.any { it in '\u3400'..'\u9FFF' } -> "zh"
        value.any(::isVietnameseSpecificLetter) -> "vi"
        else -> null
    }

    private fun isVietnameseSpecificLetter(char: Char): Boolean = char.lowercaseChar() in VIETNAMESE_SPECIFIC_LETTERS

    private fun normalizeLanguageCode(tag: String): String = tag.substringBefore('-').lowercase()

    private fun recognizerFor(script: OcrScript): TextRecognizer = when (script) {
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private data class ElementPart(val text: String, val box: Rect)
    private data class ClassifiedPart(val part: ElementPart, val role: LanguageRole)
    private data class Segment(val text: String, val box: Rect, val translate: Boolean)
    private data class LinePlan(val box: Rect, val segments: List<Segment>)
    private enum class LanguageRole { SOURCE, TARGET, OTHER, NEUTRAL }

    private companion object {
        const val LANGUAGE_CONFIDENCE = 0.20f
        const val MIN_GLYPH_DIFFERENCE = 80.0
        val CLOSING_PUNCTUATION = setOf('.', ',', ':', ';', '!', '?', ')', ']', '}', '%')
        val OPENING_PUNCTUATION = setOf('(', '[', '{')
        val VIETNAMESE_SPECIFIC_LETTERS =
            "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ".toSet()
    }
}
