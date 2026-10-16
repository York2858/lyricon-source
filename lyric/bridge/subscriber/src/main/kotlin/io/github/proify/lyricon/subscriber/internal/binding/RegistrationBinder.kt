/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.subscriber.internal.binding

import android.util.Log
import io.github.proify.lyricon.subscriber.IRemoteService
import io.github.proify.lyricon.subscriber.ISubscriberBinder
import io.github.proify.lyricon.subscriber.SubscriberInfo
import io.github.proify.lyricon.subscriber.internal.wire.json

/**
 * 订阅端暴露给中心服务的 AIDL 适配器。
 *
 * 只负责传递注册信息和接收中心服务返回的远端服务 Binder。
 * 尝试由 [RegistrationAttempts] 托管：尝试被取消（注销/销毁/超时）后，
 * 迟到的注册回调将被忽略，不会建立连接，保证回调至多被消费一次。
 *
 * @property subscriberInfo 订阅端注册信息，序列化后通过 [getSubscriberInfo] 上报。
 */
internal class RegistrationBinder(
    private val subscriberInfo: SubscriberInfo
) : ISubscriberBinder.Stub() {

    private val attempts = RegistrationAttempts<RegistrationAttempt>()

    private val serializedSubscriberInfo by lazy {
        json.encodeToString(subscriberInfo).toByteArray()
    }

    /** 进入等待中心服务回调的状态。 */
    fun beginAttempt(attempt: RegistrationAttempt) {
        attempts.begin(attempt)
    }

    /** 结束等待：仅在 [attempt] 仍为当前尝试时清除。 */
    fun endAttempt(attempt: RegistrationAttempt) {
        attempts.end(attempt)
    }

    override fun onRegistrationCallback(service: IRemoteService?) {
        // 原子地消费本次尝试：迟到回调（尝试已被取消）或重复回调（已被消费）
        // 在这里被拦截。
        val attempt = attempts.consume() ?: run {
            Log.w(TAG, "Ignoring registration callback (attempt was cancelled)")
            return
        }
        attempt.onCompleted(service)
    }

    override fun getSubscriberInfo(): ByteArray = serializedSubscriberInfo

    /** 一次注册尝试：中心服务回调返回后触发。 */
    interface RegistrationAttempt {
        /**
         * 回调已送达。
         *
         * @param service 中心服务返回的远端服务，可能为 null。
         */
        fun onCompleted(service: IRemoteService?)
    }

    private companion object {
        private const val TAG = "RegistrationBinder"
    }
}
