/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.subscriber

import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import io.github.proify.lyricon.central.internal.wire.json
import io.github.proify.lyricon.central.provider.player.ActivePlayerListener
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.subscriber.IActivePlayerListener
import io.github.proify.lyricon.subscriber.SubscriberInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 单个订阅端的活跃播放器会话。
 *
 * 作为 [ActivePlayerListener] 注册到活跃播放器中枢，把状态事件转换为
 * 订阅端 AIDL 回调；位置经共享内存写入，歌曲/提供端信息序列化后传输。
 *
 * @property subscriberInfo 订阅端身份，用于共享内存命名。
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SubscriberSession(
    subscriberInfo: SubscriberInfo
) : ActivePlayerListener {

    /** 订阅端的活跃播放器监听器。 */
    @Volatile
    var remoteListener: IActivePlayerListener? = null

    /** 位置共享内存（通过 AIDL 返回给订阅端）。 */
    var positionMemory: SharedMemory? = null
        private set

    /** [positionMemory] 的读写映射缓冲。 */
    private var positionBuffer: ByteBuffer? = null

    init {
        initializePositionMemory(subscriberInfo)
    }

    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        if (providerInfo == null) {
            remoteListener?.onActiveProviderChanged(null)
            return
        }

        val out = ByteArrayOutputStream()
        json.encodeToStream(providerInfo, out)
        remoteListener?.onActiveProviderChanged(out.toByteArray())
    }

    override fun onSongChanged(song: Song?) {
        val bytes = song?.let { json.encodeToString(it).toByteArray() } ?: byteArrayOf()
        remoteListener?.onSongChanged(bytes)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        remoteListener?.onPlaybackStateChanged(isPlaying)
    }

    override fun onPositionChanged(position: Long) {
        try {
            positionBuffer?.putLong(0, position)
        } catch (e: Exception) {
            Log.e(TAG, "Position write failed", e)
        }
    }

    override fun onSeekTo(position: Long) {
        remoteListener?.onSeekTo(position)
    }

    override fun onSendText(text: String?) {
        remoteListener?.onReceiveText(text)
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        remoteListener?.onDisplayTranslationChanged(isDisplayTranslation)
    }

    override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
        remoteListener?.onDisplayRomaChanged(isDisplayRoma)
    }

    /** 释放共享内存并清空监听引用。 */
    fun close() {
        positionBuffer = null
        positionMemory?.close()
        positionMemory = null
        remoteListener = null
    }

    /** 按订阅端身份创建位置共享内存并映射。 */
    private fun initializePositionMemory(info: SubscriberInfo) {
        try {
            val hashHex = Integer.toHexString(
                "${info.packageName}/${info.processName}".hashCode()
            )
            positionMemory =
                SharedMemory.create("lyricon_subscriber_pos_$hashHex", Long.SIZE_BYTES).apply {
                    setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)
                    positionBuffer = mapReadWrite()
                }
        } catch (t: Throwable) {
            Log.e(TAG, "SharedMemory mapping failed", t)
        }
    }

    private companion object {
        private const val TAG = "SubscriberSession"
    }
}
