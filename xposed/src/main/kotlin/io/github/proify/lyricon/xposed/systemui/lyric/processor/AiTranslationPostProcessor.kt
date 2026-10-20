/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric.processor

import android.util.Log
import io.github.proify.lyricon.lyric.ai.translate.TranslationDiagnostic
import io.github.proify.lyricon.lyric.ai.core.AiConfigProvider
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.xposed.systemui.ai.translate.AiTranslationOptions
import io.github.proify.lyricon.xposed.systemui.ai.translate.AiTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 翻译后处理器。
 *
 * 该处理器负责在歌词数据渲染前进行最后的检查与增强。
 * 如果检测到歌词缺少翻译，将根据配置调用外部 AI 服务进行在线异步翻译。
 *
 * 处理流程：
 * 1. 检查总开关及配置可用性。
 * 2. 检查是否满足"自动跳过中文歌"条件。
 * 3. 检查歌词是否已经包含完整翻译（避免重复翻译）。
 * 4. 调用 [AiTranslator] 进行同步翻译并返回新对象。
 *
 * @param configProvider AI 配置提供者，用于获取当前激活的配置
 *
 * @author Tomakino
 * @since 2026
 */
class AiTranslationPostProcessor(
    private val configProvider: AiConfigProvider,
) : PostProcessor {

    companion object {
        private const val TAG = "AiTranslationPostProcessor"
    }

    /**
     * 处理器优先级。
     * 设置为 10，确保其在基础解析器之后、视觉样式处理器之前运行。
     */
    override val priority: Int = 10

    /**
     * 判断当前样式配置下是否允许执行 AI 翻译。
     *
     * @param style 当前应用的歌词样式。
     * @return 如果功能已启用且配置有效（如 API Key 已填）则返回 true。
     */
    // The processor also records why a request was not started.
    override fun isEnabled(style: LyricStyle): Boolean = true

    /**
     * 执行翻译处理核心逻辑。
     *
     * 该方法会在协程中运行，通过 [AiTranslator] 发起 IO 请求。
     * 为保证数据一致性，在翻译前会进行 [Song] 对象的快照比对，若翻译结果无变化则返回原对象。
     *
     * @param song 原始歌曲数据对象。
     * @param style 当前关联的样式。
     * @return 处理后的歌曲对象（若无需翻译或翻译失败则返回原对象）。
     */
    override suspend fun process(song: Song, style: LyricStyle): Song = process(song, style) {}

    override suspend fun process(song: Song, style: LyricStyle, onProgress: (Song) -> Unit): Song {
        val settings = style.basicStyle
        val translationOptions = AiTranslationOptions(settings.aiTranslationTargetLanguage, settings.aiTranslationPrompt,
            settings.googleTranslationFallback, settings.googleTranslationBaseUrl)
        suspend fun skipped(code: String, reason: String, advice: String): Song {
            AiTranslator.recordOutcome(song, "skipped", TranslationDiagnostic(code, reason, advice), options = translationOptions)
            return song
        }
        if (!song.lyrics.isNullOrEmpty()) AiTranslator.resolveWaitingForLyrics(song)
        if (!settings.isAiTranslationEnable) return skipped("DISABLED", "AI 翻译未开启", "到词幕的 AI 实验室开启 AI 翻译。")
        if (song.lyrics.isNullOrEmpty()) return skipped("NO_LYRICS", "当时尚未收到歌词，可能正在切歌或匹配", "播放器可能先发送歌曲信息，再发送完整歌词；歌词到达后会自动继续检查，并更新此记录。")
        if (settings.isAiTranslationAutoIgnoreChinese && song.isFullyChinese())
            return skipped("CHINESE", "整首歌词为中文，已按“跳过中文”设置跳过；数字、标点不影响判定", "需要翻译中文歌时，可在 AI 实验室关闭“跳过中文”。")
        if (isFullyTranslated(song)) return skipped("ALREADY_TRANSLATED", "来源歌词的每一行都已有翻译", "直接使用已有译文，无需再次调用翻译服务。")
        if (song.lyrics.orEmpty().none { line -> line.text.orEmpty().any { it.isLetter() } })
            return skipped("NO_TEXT", "歌词只有空白、数字或标点，没有需要翻译的文字", "等待有实际歌词文字的歌曲，无需修改翻译配置。")
        val translationConfig = configProvider.getActiveConfig()
        if (translationConfig?.isUsable != true) {
            val missing = if (translationConfig == null) "尚未选择可用的 AI 提供商" else buildList {
                if (translationConfig.apiKey.isNullOrBlank()) add("API Key")
                if (translationConfig.baseUrl.isNullOrBlank()) add("接口地址")
                if (translationConfig.model.isNullOrBlank()) add("模型名称")
            }.joinToString("、") + "未填写"
            return skipped("CONFIG_MISSING", missing, "到 AI 实验室补全提供商配置。Google 仅在 AI 请求实际失败后保底。")
        }

        return withContext(Dispatchers.IO) {
            Log.i(TAG, "Starting AI translation for: ${song.name} - ${song.artist}")

            val translatedSong = try {
                AiTranslator.translateSongSync(song, translationConfig, translationOptions, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI translation failed for [${song.name}]: ${e.message}")
                return@withContext song
            }

            // 变更检测：如果翻译内容与原内容一致，返回旧引用以优化内存和 UI 刷新
            if (translatedSong === song || translatedSong.lyrics == song.lyrics) {
                Log.d(TAG, "Translation completed but no content changed for [${song.name}].")
                return@withContext song
            }

            Log.i(TAG, "Successfully translated: ${song.name}")
            return@withContext translatedSong
        }
    }

    /**
     * 内部辅助：检查歌曲的所有歌词行是否均已填充有效翻译。
     */
    private fun isFullyTranslated(song: Song): Boolean {
        return song.lyrics?.all { lyricLine ->
            !lyricLine.translation.isNullOrBlank()
        } ?: false
    }

    /**
     * 内部辅助：判断整首歌词是否为中文（忽略数字、空白和标点）。
     */
    private fun Song.isFullyChinese(): Boolean {
        return lyrics?.all { line ->
            line.text?.filterNot { it.isWhitespace() || it.isPunctuation() || it.isDigit() }
                ?.all { it.isChinese() } ?: true
        } ?: true
    }

    /**
     * 字符级中文判定。
     * 使用 Unicode 字符块识别常见汉字及符号。
     */
    private fun Char.isChinese(): Boolean {
        val ub = Character.UnicodeBlock.of(this)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
                ub == Character.UnicodeBlock.GENERAL_PUNCTUATION
    }

    /**
     * 判定字符是否为标点符号或非字母数字字符。
     */
    private fun Char.isPunctuation(): Boolean {
        return !isLetterOrDigit() && !isWhitespace()
    }
}
