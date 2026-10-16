/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.internal.connection

import android.os.IBinder
import android.util.Log

/**
 * Binder 死亡跟踪器：为当前 Binder 维护死亡监听，并支持换绑。
 *
 * 死亡回调携带"死去的 Binder"：只有死者仍是当前 Binder 时才会触发。
 * 旧 Binder 的迟到死亡通知（例如提供端进程重启后已用新 Binder 重新注册）
 * 不会误触发回调，从而避免粘性死亡黑洞。
 *
 * 触发后的最终处置（原子校验 + 拆除）由持有者在 [onDeath] 中完成。
 */
internal class BinderDeathTracker {

    /** 当前 Binder 死亡时触发，参数为死去的 Binder。 */
    @Volatile
    var onDeath: ((IBinder) -> Unit)? = null

    @Volatile
    private var linkedBinder: IBinder? = null

    private var recipient: IBinder.DeathRecipient? = null

    /** 将监听改绑到 [binder]；null 表示解除全部监听。 */
    fun track(binder: IBinder?) {
        val oldRecipient = recipient
        val oldBinder = linkedBinder
        recipient = null
        linkedBinder = null

        if (oldRecipient != null && oldBinder != null) {
            runCatching { oldBinder.unlinkToDeath(oldRecipient, 0) }
                .onFailure { Log.w(TAG, "Failed to unlink death recipient", it) }
        }

        val next = binder ?: return
        linkedBinder = next
        val nextRecipient = IBinder.DeathRecipient {
            // 只处理当前 Binder 的死亡：闭包绑定了链接时的 Binder。
            if (linkedBinder === next) onDeath?.invoke(next)
        }
        runCatching { next.linkToDeath(nextRecipient, 0) }
            .onFailure { Log.e(TAG, "Failed to link death recipient", it) }
            .onSuccess { recipient = nextRecipient }
    }

    /** 解除全部死亡监听。 */
    fun detach() = track(null)

    private companion object {
        private const val TAG = "BinderDeathTracker"
    }
}