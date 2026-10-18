package io.github.proify.lyricon.lyric.ai.translate

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class TranslationLyricsExportTest {
    private val version = TranslationVersion("id", "海 & 风", "A < B", "中文", "model", 1, 1, false, true, true, true)
    private val line = TranslationLine(5, "hello world", "你好世界", 61234, 64000,
        listOf(TranslationWord("hello ", 61234, 62200), TranslationWord("world", 62300, 63789)))
    private fun export(format: TranslationExportFormat, content: TranslationExportContent = TranslationExportContent.BILINGUAL,
        value: TranslationLine = line) = exportTranslationLyrics(version, listOf(value), TranslationExportOptions(format, content))

    @Test fun lineLrcKeepsMillisecondsAndSynchronizesTranslation() {
        val text = export(TranslationExportFormat.LINE)
        assertTrue(text.contains("[01:01.234]hello world[01:04.000]"))
        assertTrue(text.contains("[01:01.234]你好世界[01:04.000]"))
        assertTrue(text.contains("[ti:海 & 风]"))
    }
    @Test fun verbatimAndEnhancedUseTheReferenceTimestampConventions() {
        assertTrue(export(TranslationExportFormat.WORD).contains("[01:01.234]hello [01:02.300]world[01:03.789]"))
        val enhanced = export(TranslationExportFormat.ENHANCED)
        assertTrue(enhanced.contains("[01:01.234]<01:01.234>hello <01:02.300>world<01:03.789>"))
        assertTrue(enhanced.contains("[01:01.234]你好世界[01:04.000]"))
        assertFalse(enhanced.contains("<01:01.234>你好"))
    }
    @Test fun missingOrMismatchedWordTimingFallsBackToRealLineTiming() {
        val missing = line.copy(words = emptyList())
        assertTrue(export(TranslationExportFormat.WORD, value = missing).contains("[01:01.234]hello world[01:04.000]"))
        assertFalse(export(TranslationExportFormat.ENHANCED, value = missing).contains("<01:"))
        val invalid = line.copy(words = listOf(TranslationWord("different", 0, 1)))
        assertFalse(export(TranslationExportFormat.ENHANCED, value = invalid).contains("different"))
    }
    @Test fun ttmlIsWellFormedEscapesXmlAndKeepsTranslationAttachedToOriginal() {
        val text = export(TranslationExportFormat.TTML, value = line.copy(translation = "<&>\"'\u0001 😀"))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals("http://www.w3.org/ns/ttml", doc.documentElement.namespaceURI)
        val p = doc.getElementsByTagNameNS("http://www.w3.org/ns/ttml", "p").item(0) as org.w3c.dom.Element
        assertEquals("00:01:01.234", p.getAttribute("begin"))
        assertEquals("00:01:04.000", p.getAttribute("end"))
        val spans = p.getElementsByTagNameNS("http://www.w3.org/ns/ttml", "span")
        assertEquals(3, spans.length)
        val translation = spans.item(2) as org.w3c.dom.Element
        assertEquals("x-translation", translation.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role"))
        assertEquals("<&>\"' 😀", translation.textContent)
        assertTrue(text.contains("海 &amp; 风")); assertTrue(text.contains("A &lt; B"))
    }
    @Test fun contentOptionsApplyToAllFiveFormats() {
        TranslationExportFormat.entries.forEach { format ->
            val original = export(format, TranslationExportContent.ORIGINAL)
            assertTrue(original.contains("hello")); assertFalse(original.contains("你好世界"))
            val translated = export(format, TranslationExportContent.TRANSLATION)
            assertTrue(translated.contains("你好世界")); assertFalse(translated.contains("hello"))
        }
    }
    @Test fun untimedRecordsCanExportTextWithoutInventingTimestamps() {
        val untimed = line.copy(begin = null, end = null, words = emptyList())
        assertTrue(export(TranslationExportFormat.TXT, value = untimed).contains("hello world"))
        TranslationExportFormat.entries.filter { it != TranslationExportFormat.TXT }.forEach {
            try { export(it, value = untimed); fail("Should reject missing timing") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("时间轴")) }
        }
        val legacy = untimed.copy(original = "")
        assertTrue(export(TranslationExportFormat.TXT, TranslationExportContent.TRANSLATION, legacy).contains("你好世界"))
    }
    @Test fun filenameUsesTheRealFormatAndIsSafeForDocumentProviders() {
        val song = version.copy(artist = "杏里", title = "WINDY SUMMER")
        TranslationExportFormat.entries.forEach { format ->
            TranslationExportContent.entries.forEach { content ->
                assertEquals("杏里 - WINDY SUMMER.${format.extension}",
                    translationExportFileName(song, TranslationExportOptions(format, content)))
            }
        }
        val name = translationExportFileName(version.copy(title = "a/b:c?d\n"), TranslationExportOptions(TranslationExportFormat.TTML))
        assertTrue(name.endsWith(".ttml")); assertFalse(name.contains('/')); assertFalse(name.contains(':')); assertFalse(name.contains('\n'))
    }
    @Test fun localAudioFilenameKeepsTrackNumberAndDotsAcrossAllFormats() {
        TranslationExportFormat.entries.forEach { format ->
            assertEquals("02. WINDY SUMMER.${format.extension}", translationExportFileName(version,
                TranslationExportOptions(format), "02. WINDY SUMMER.m4a"))
        }
        assertEquals("my.custom.song.lrc", translationExportFileName(version,
            TranslationExportOptions(TranslationExportFormat.LINE), "my.custom.song.flac"))
    }
    @Test fun realAudioBasenameIsNotRenamedOrTruncatedAndMissingNamesUseMetadata() {
        val options = TranslationExportOptions(TranslationExportFormat.LINE)
        val baseName = "02. " + "夏日".repeat(65) + " Live: 2026? "
        assertEquals("$baseName.lrc", translationExportFileName(version, options, "$baseName.flac"))
        listOf(null, "", " ").forEach { missing ->
            assertEquals(translationExportFileName(version, options), translationExportFileName(version, options, missing))
        }
    }
}
