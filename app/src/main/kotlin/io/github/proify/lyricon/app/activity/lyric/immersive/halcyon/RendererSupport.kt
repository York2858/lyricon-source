package io.github.proify.lyricon.app.activity.lyric.immersive.halcyon

internal const val PLAYER_POSITION_BACKWARD_DRIFT_TOLERANCE_MS = 600L

internal fun isUltraWideLandscapePlayerLayout(screenWidthDp: Int, screenHeightDp: Int): Boolean {
    if (screenWidthDp <= 0 || screenHeightDp <= 0) return false
    return screenWidthDp > screenHeightDp && screenWidthDp.toFloat() / screenHeightDp >= 2.45f
}

internal data class MiniLyricLinePresentation(
    val showPrimaryText: Boolean,
    val showTranslation: Boolean,
    val showPronunciation: Boolean,
    val showBackgroundText: Boolean = false
)

internal fun Char.isKanjiOrHangul(): Boolean = Character.UnicodeBlock.of(this) in setOf(
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
    Character.UnicodeBlock.HANGUL_SYLLABLES,
    Character.UnicodeBlock.HANGUL_JAMO,
    Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
)

internal fun String.isRtlText(): Boolean {
    var rtlCount = 0
    var ltrCount = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        when (Character.getDirectionality(codePoint).toInt()) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt(),
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt() -> rtlCount++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt() -> ltrCount++
        }
        index += Character.charCount(codePoint)
    }
    return rtlCount > 0 && rtlCount >= ltrCount
}

internal fun String.needsPhoneticAnnotation(): Boolean {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        index += Character.charCount(codePoint)
        if (!Character.isLetter(codePoint)) continue
        if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) return true
    }
    return false
}
