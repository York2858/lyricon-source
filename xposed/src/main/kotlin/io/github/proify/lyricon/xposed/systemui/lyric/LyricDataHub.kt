/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.lyric

import android.util.Log
import android.media.MediaMetadata
import android.media.session.MediaController
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.style.LyricStyle
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ProviderInfo
import io.github.proify.lyricon.xposed.systemui.lyric.processor.LyricDataProcessor
import io.github.proify.lyricon.xposed.systemui.util.SystemUIMediaUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * 歌词数据调度中枢
 * 管理歌词生命周期：获取原始数据 -> 后台加工 -> 第一次分发 -> 后台增强 -> 最终分发。
 */
object LyricDataHub : ActivePlayerListener {
    private const val TAG = "LyricDataHub"

    private val listeners = CopyOnWriteArraySet<ActivePlayerListener>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 状态版本号，用于在异步恢复后校验数据是否已过时 */
    private val versionCounter = AtomicInteger(0)

    /** 缓存当前的原始歌曲，用于配置变更时重走流程 */
    private var cachedRawSong: Song? = null

    /** Shared enhanced lyrics, before display-only transformations such as translation-only. */
    private var cachedEnrichedSong: Song? = null

    /** 当前执行中的流水线任务 */
    private var activePipelineJob: Job? = null

    /** 当前活动的提供者信息 */
    private var providerInfo: ProviderInfo? = null

    private val trackProgressGate = TrackProgressGate()
    private var pendingPublication = false
    private var pendingSourceSong = false
    private var playing = false

    private val sourceMetadataFallback = Runnable {
        if (pendingSourceSong) {
            // Some providers update lyrics before (or without) updating MediaSession metadata.
            // Never discard their latest lyrics or leave the display stalled indefinitely.
            trackProgressGate.reset()
            trackProgressGate.acceptLyrics(cachedRawSong?.name, cachedRawSong?.artist)
            pendingSourceSong = false
            runProcessingPipeline(cachedRawSong)
        }
    }

    val isWaitingForTrack: Boolean
        get() = pendingPublication || trackProgressGate.waitingForLyrics

    init {
        SystemUIMediaUtils.registerListener(object : SystemUIMediaUtils.MediaControllerCallback {
            override fun onMediaChanged(controller: MediaController, metadata: MediaMetadata) {
                if (controller.packageName != providerInfo?.playerPackageName) return
                updateMediaTrack(metadata)
                if (pendingSourceSong) processCurrentSourceSong()
            }
        })
    }

    private fun updateMediaTrack(metadata: MediaMetadata?) {
        trackProgressGate.updateMedia(
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        )
    }

    fun addListener(listener: ActivePlayerListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ActivePlayerListener) {
        listeners.remove(listener)
    }

    /**
     * 启动后台加工流水线。
     * 所有歌词加工都在后台协程执行，避免阻塞播放器回调线程。
     * @param rawSong 待加工的原始歌曲
     */
    private fun runProcessingPipeline(rawSong: Song?) {
        val currentVersion = versionCounter.incrementAndGet()
        activePipelineJob?.cancel()
        pendingPublication = true

        activePipelineJob = scope.launch {
            try {
                val song = rawSong?.deepCopy()
                if (song == null) {
                    publishSong(null, currentVersion)
                    return@launch
                }

                // AI translation is configured globally in AI Lab, independently of display mode.
                val processingStyle = LyricPrefs.getLyricStyle()

                // 1. 后台前置加工：处理繁简、屏蔽词等基础显示数据
                val preProcessed = LyricDataProcessor.executePreProcessing(song)
                if (!isCurrentVersion(currentVersion)) return@launch

                // 第一次分发：基础处理完成后尽快刷新 UI
                publishSong(preProcessed, currentVersion)

                // 2. 后台后置流水线：处理 AI 翻译等耗时扩展
                val finalSong =
                    LyricDataProcessor.executePostProcessingPipeline(preProcessed, processingStyle) { partial ->
                        publishSong(partial, currentVersion)
                    }

                // The preferred result may equal the original while Google was shown in between.
                if (isCurrentVersion(currentVersion)) {
                    publishSong(finalSong, currentVersion)
                } else {
                    logOutdatedPipeline(currentVersion, finalSong)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Pipeline $currentVersion cancelled. $e")
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline $currentVersion error", e)
            }
        }
    }

    private fun isCurrentVersion(version: Int): Boolean = version == versionCounter.get()

    private fun currentDisplayStyle(): LyricStyle =
        if (LyricPrefs.floatingBaseStyle.manualFloatingLyricEnabled) LyricPrefs.floatingLyricStyle
        else LyricPrefs.getLyricStyle()

    private fun publishSong(song: Song?, version: Int) {
        StatusBarViewManager.runOnMainThread {
            if (!isCurrentVersion(version)) return@runOnMainThread
            if (trackProgressGate.waitingForLyrics) return@runOnMainThread
            cachedEnrichedSong = song
            pendingPublication = false
            dispatchSong(song?.let { LyricDataProcessor.executeDisplayProcessing(it, currentDisplayStyle()) })
            listeners.forEach { it.onPlaybackStateChanged(playing) }
        }
    }

    /** Reuse the completed translation and current position when only the display location changes. */
    fun refreshDisplayForMode() {
        if (isWaitingForTrack) return
        val song = cachedEnrichedSong ?: return
        dispatchSong(LyricDataProcessor.executeDisplayProcessing(song, currentDisplayStyle()))
        LyricViewController.restoreCurrentPosition()
    }

    private fun logOutdatedPipeline(version: Int, song: Song) {
        Log.d(
            TAG,
            "Pipeline requestVersion:$version, nowVersion:${versionCounter.get()} skipped. ${song.name}"
        )
    }

    /**
     * 重走加工流程
     * 当配置（繁简、AI 开关、翻译模式）变更时调用，无需切歌即可应用新设置。
     */
    fun reprocessCurrentSong() {
        StatusBarViewManager.runOnMainThread { runProcessingPipeline(cachedRawSong) }
    }

    // --- ActivePlayerListener 触发点 ---

    override fun onSongChanged(song: Song?) {
        StatusBarViewManager.runOnMainThread {
            // Consult unmodified source metadata; display conversion may change a song title.
            updateMediaTrack(SystemUIMediaUtils.getController(providerInfo?.playerPackageName)?.metadata)
            activePipelineJob?.cancel()
            versionCounter.incrementAndGet()
            this.cachedRawSong = song?.deepCopy()
            processCurrentSourceSong()
        }
    }

    private fun processCurrentSourceSong() {
        val song = cachedRawSong
        val accepted = trackProgressGate.acceptLyrics(song?.name, song?.artist)
        if (!accepted || trackProgressGate.waitingForLyrics) {
            Log.i(TAG, "Waiting for media/lyric track agreement: ${song?.name}")
            pendingPublication = true
            pendingSourceSong = true
            StatusBarViewManager.mainHandler.removeCallbacks(sourceMetadataFallback)
            StatusBarViewManager.mainHandler.postDelayed(sourceMetadataFallback, 750L)
            return
        }
        pendingSourceSong = false
        StatusBarViewManager.mainHandler.removeCallbacks(sourceMetadataFallback)
        runProcessingPipeline(song)
    }

    private var lastDispatchSongId = 0
    private fun dispatchSong(song: Song?) {
        val normalize = song?.normalize()

        val hashCode = normalize?.hashCode() ?: 0
        if (hashCode == lastDispatchSongId) return
        lastDispatchSongId = hashCode

        listeners.forEach { it.onSongChanged(normalize) }
    }

    // --- 纯状态透传 (不涉及加工) ---

    override fun onReceiveText(text: String?) {
        listeners.forEach { it.onReceiveText(text) }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        StatusBarViewManager.runOnMainThread {
            playing = isPlaying
            if (!isWaitingForTrack) listeners.forEach { it.onPlaybackStateChanged(isPlaying) }
        }
    }

    override fun onPositionChanged(position: Long) {
        StatusBarViewManager.runOnMainThread {
            if (!isWaitingForTrack) listeners.forEach { it.onPositionChanged(position) }
        }
    }

    override fun onSeekTo(position: Long) {
        StatusBarViewManager.runOnMainThread {
            if (!isWaitingForTrack) listeners.forEach { it.onSeekTo(position) }
        }
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        listeners.forEach { it.onDisplayTranslationChanged(isDisplayTranslation) }
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        listeners.forEach { it.onDisplayRomaChanged(isDisplayRoma) }
    }

    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        StatusBarViewManager.runOnMainThread {
            this.providerInfo = providerInfo
            trackProgressGate.reset()
            activePipelineJob?.cancel()
            versionCounter.incrementAndGet()
            pendingPublication = false
            pendingSourceSong = false
            StatusBarViewManager.mainHandler.removeCallbacks(sourceMetadataFallback)
            cachedRawSong = null
            cachedEnrichedSong = null
            lastDispatchSongId = 0
            listeners.forEach { it.onActiveProviderChanged(providerInfo) }
        }
    }
}
