package com.dangtuan.btranslate.translation

data class LanguageOption(val label: String, val code: String, val ocr: OcrScript = OcrScript.LATIN)
enum class OcrScript { LATIN, CHINESE, JAPANESE, KOREAN }

object LanguageCatalog {
    val sources = listOf(
        LanguageOption("Tiếng Anh", "en"),
        LanguageOption("Tiếng Việt", "vi"),
        LanguageOption("Tiếng Pháp", "fr"),
        LanguageOption("Tiếng Đức", "de"),
        LanguageOption("Tiếng Tây Ban Nha", "es"),
        LanguageOption("Tiếng Indonesia", "id"),
        LanguageOption("Tiếng Trung", "zh", OcrScript.CHINESE),
        LanguageOption("Tiếng Nhật", "ja", OcrScript.JAPANESE),
        LanguageOption("Tiếng Hàn", "ko", OcrScript.KOREAN)
    )

    val targets = sources
}
