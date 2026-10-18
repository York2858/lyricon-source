/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.activity.lyric

import android.content.SharedPreferences
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.ai.explain.AiExplainPrompt
import io.github.proify.lyricon.app.compose.IconActions
import io.github.proify.lyricon.app.compose.preference.StringInputPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * AI 音乐解读设置（功能级配置）。
 *
 * 提供自定义系统提示词的设置选项，允许用户自定义音乐解读的风格和内容。
 *
 * @author Tomakino
 * @since 2026
 */
@Composable
fun AiExplainPreference(preferences: SharedPreferences) {
    val context = LocalContext.current
    Column {
    ArrowPreference(
        title = "专辑解读",
        summary = "按曲序逐首解读歌词、编曲与律动，保存并继续阅读整张专辑",
        startAction = { IconActions(painterResource(R.drawable.ic_music_note)) },
        onClick = { context.startActivity(Intent(context, AiExplainActivity::class.java)
            .putExtra(AiExplainActivity.EXTRA_OPEN_ALBUM, true)) },
    )
    ArrowPreference(
        title = "解读历史与收藏",
        summary = "按歌曲查找解读，回看收藏的版本",
        startAction = { IconActions(painterResource(R.drawable.explain_history)) },
        onClick = { context.startActivity(Intent(context, AiExplainActivity::class.java)
            .putExtra(AiExplainActivity.EXTRA_OPEN_HISTORY, true)) },
    )
    ArrowPreference(
        title = "快捷追问设置",
        summary = "自定义“继续聊聊”里的快捷问题",
        startAction = { IconActions(painterResource(R.drawable.psychology_24px)) },
        onClick = { context.startActivity(Intent(context, AiExplainActivity::class.java)
            .putExtra(AiExplainActivity.EXTRA_OPEN_QUICK_PROMPTS, true)) },
    )
    StringInputPreference(
        preferences = preferences,
        key = AiExplainPrompt.KEY_CUSTOM_SYSTEM_PROMPT,
        title = stringResource(R.string.item_ai_explain_custom_prompt),
        dialogSummary = stringResource(R.string.dialog_summary_ai_explain_custom_prompt),
        defaultValue = AiExplainPrompt.DEFAULT_SYSTEM_PROMPT,
        startAction = { IconActions(painterResource(R.drawable.psychology_24px)) },
    )
    }
}
