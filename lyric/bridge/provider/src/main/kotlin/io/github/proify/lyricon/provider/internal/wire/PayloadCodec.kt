/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.internal.wire

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** 模块内跨进程载荷统一使用的宽松 JSON 编解码器。 */
internal val json: Json = Json {
    coerceInputValues = true     // 尝试转换类型
    ignoreUnknownKeys = true     // 忽略未知字段
    isLenient = true             // 宽松的 JSON 语法
    explicitNulls = false        // 不序列化 null
    encodeDefaults = false       // 不序列化默认值
}

/** 使用 ZLIB 压缩字节数组。 */
internal fun ByteArray.deflate(): ByteArray {
    if (isEmpty()) return byteArrayOf()

    val compressor = Deflater(Deflater.BEST_SPEED)
    return try {
        compressor.setInput(this)
        compressor.finish()
        ByteArrayOutputStream(size.coerceAtMost(64 * 1024)).use { output ->
            val buffer = ByteArray(8192)
            while (!compressor.finished()) {
                output.write(buffer, 0, compressor.deflate(buffer))
            }
            output.toByteArray()
        }
    } finally {
        compressor.end()
    }
}
