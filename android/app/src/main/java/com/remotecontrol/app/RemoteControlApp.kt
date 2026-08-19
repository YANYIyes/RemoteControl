package com.remotecontrol.app

import android.app.Application

/**
 * 应用级单例: 全局共享一个 WebSocket 连接
 * 避免 MainActivity / ScreenActivity 各自创建连接
 */
class RemoteControlApp : Application() {
    companion object {
        lateinit var ws: WsClient
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Companion.ws = WsClient(this)
    }
}
