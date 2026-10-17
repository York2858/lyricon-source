/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.activity.lyric

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.activity.lyric.pkg.page.AnimPage
import io.github.proify.lyricon.app.activity.lyric.pkg.page.LogoPage
import io.github.proify.lyricon.app.activity.lyric.pkg.page.TextPage
import io.github.proify.lyricon.app.compose.AppToolBarContainer
import io.github.proify.lyricon.app.compose.custom.miuix.basic.MiuixScrollBehavior
import io.github.proify.lyricon.app.compose.custom.miuix.basic.ScrollBehavior
import io.github.proify.lyricon.app.util.LyricPrefs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults

/**
 * 悬浮窗歌词的「颜色和动画」配置页。
 *
 * 与包级样式页结构一致，但读写的是悬浮窗专属的偏好文件，
 * 且仅展示与副行渲染相关的新增选项。
 */
class FloatingColorStyleActivity : AbstractLyricActivity() {

    private val preferences: SharedPreferences by lazy {
        LyricPrefs.floatingPackageStylePrefs
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Content()
        }
    }

    override fun onResume() {
        super.onResume()
        // 页面内改动即时下发，无需退出页面才生效
        preferences.registerOnSharedPreferenceChangeListener(LyricPrefs.floatingStyleChangeListener)
    }

    override fun onPause() {
        super.onPause()
        preferences.unregisterOnSharedPreferenceChangeListener(
            LyricPrefs.floatingStyleChangeListener
        )
        // 悬浮窗颜色/动画变更后通知模块刷新（跨进程偏好监听不生效）
        LyricPrefs.cancelScheduledFloatingStyleNotify()
        LyricPrefs.notifyFloatingStyleChanged()
    }

    @Composable
    private fun Content() {
        val scrollBehavior = MiuixScrollBehavior()
        val hazeState: HazeState = rememberHazeState()
        val pagerState = rememberPagerState(pageCount = { 3 })

        AppToolBarContainer(
            title = stringResource(R.string.activity_floating_color_style),
            canBack = true,
            scrollBehavior = scrollBehavior,
            hazeState = hazeState,
            actions = {},
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                val tabs = listOf(
                    stringResource(R.string.tab_style_text),
                    stringResource(R.string.tab_style_icon),
                    stringResource(R.string.tab_style_anim),
                )
                val selectedTabIndex = remember { mutableIntStateOf(0) }
                val coroutineScope = rememberCoroutineScope()

                TabRow(
                    height = 43.dp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    tabs = tabs,
                    selectedTabIndex = selectedTabIndex.intValue,
                    onTabSelected = { index ->
                        selectedTabIndex.intValue = index
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    colors = TabRowDefaults.tabRowColors().copy(
                        backgroundColor = Color.Transparent
                    ),
                )

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }
                        .collect { selectedTabIndex.intValue = it }
                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> TextPage(
                            scrollBehavior = scrollBehavior,
                            preferences = preferences
                        )
                        1 -> LogoPage(scrollBehavior, preferences)
                        2 -> AnimPage(scrollBehavior, preferences)
                        else -> Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
