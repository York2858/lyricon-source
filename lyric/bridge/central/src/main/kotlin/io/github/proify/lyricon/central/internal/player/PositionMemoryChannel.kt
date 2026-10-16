/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.player

import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import io.github.proify.lyricon.provider.ProviderInfo
import java.nio.ByteBuffer

/**
 * 提供端位置共享内存通道：创建以提供端身份命名的共享内存并映射只读缓冲。
 *
 * 提供端通过 AIDL 取得同一内存对象高频写入位置，中央侧低频读取。
 *
 * @property providerInfo 提供端身份，用于共享内存命名。
 */
internal class PositionMemoryChannel(
    private val providerInfo: ProviderInfo
) {

    private var readBuffer: ByteBuffer? = null

    /** 映射后的共享内存（通过 AIDL 返回给提供端）。 */
    var sharedMemory: SharedMemory? = null
        private set

    init {
        initialize()
    }

    /** 读取最近一次写入的位置（毫秒，非负）；失败时返回 0。 */
    fun readPosition(): Long = try {
        readBuffer?.getLong(POSITION_OFFSET)?.coerceAtLeast(0L) ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /** 释放共享内存与映射。 */
    fun close() {
        readBuffer?.let { runCatching { SharedMemory.unmap(it) } }
        sharedMemory?.close()
        readBuffer = null
        sharedMemory = null
    }

    /** 按提供端身份创建共享内存。 */
    private fun initialize() {
        try {
            val hashHex = Integer.toHexString(
                "${providerInfo.providerPackageName}/${providerInfo.playerPackageName}/${providerInfo.processName}".hashCode()
            )
            sharedMemory = SharedMemory.create("lyricon_pos_$hashHex", Long.SIZE_BYTES).apply {
                setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)
                readBuffer = mapReadOnly()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SharedMemory init failed", t)
        }
    }

    private companion object {
        private const val TAG = "PositionMemoryChannel"
        private const val POSITION_OFFSET = 0
    }
}
