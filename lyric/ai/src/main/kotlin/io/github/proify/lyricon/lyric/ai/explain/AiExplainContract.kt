/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.lyric.ai.explain

/**
 * AI 音乐解读的跨进程契约：Intent extras 与目标 Activity 的单一来源。
 *
 * SystemUI 进程的 [io.github.proify.lyricon.xposed.systemui.ai.explain.AiExplainLauncher]
 * 与 App 进程的 AiExplainActivity 共同引用，避免两边各写一份字面量导致静默失配。
 */
object AiExplainContract {

    const val ACTIVITY_CLASS =
        "io.github.proify.lyricon.app.activity.lyric.AiExplainActivity"

    const val EXTRA_TITLE = "lyricon_ai_explain_title"
    const val EXTRA_ARTIST = "lyricon_ai_explain_artist"
    const val EXTRA_ALBUM = "lyricon_ai_explain_album"
    const val EXTRA_LYRICS = "lyricon_ai_explain_lyrics"
    const val EXTRA_LINE_TEXTS = "lyricon_ai_explain_line_texts"
    const val EXTRA_LINE_STARTS = "lyricon_ai_explain_line_starts"
    const val EXTRA_LINE_ENDS = "lyricon_ai_explain_line_ends"
    const val EXTRA_POSITION = "lyricon_ai_explain_position"
    const val EXTRA_COVER = "lyricon_ai_explain_cover"
    const val EXTRA_SNAPSHOT_REQUESTER = "lyricon_ai_explain_snapshot_requester"
    const val REQUEST_SNAPSHOT = 1
    const val RESULT_SNAPSHOT = 2
}
