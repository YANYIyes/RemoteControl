package com.remotecontrol.app

import android.app.Application
import android.content.Context
import android.content.Intent

/**
 * 应用级单例: 全局共享一个 WebSocket 连接
 * 避免 MainActivity / ScreenActivity 各自创建连接
 * v2.0: 启动时按设置决定是否拉起前台监控服务(后台常驻)
 */
class RemoteControlApp : Application() {
    companion object {
        lateinit var ws: WsClient
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Companion.ws = WsClient(this)

        // v2.0: 若后台常驻开启, 启动前台监控服务
        val prefs = getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("background_enabled", true)) {
            startMonitorService()
        }
    }

    /** 启动前台监控服务 (后台常驻 + 推送通知) */
    fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        startServiceCompat(intent)
    }

    /** 停止前台监控服务 */
    fun stopMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        stopService(intent)
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            startService(intent)
        } catch (e: Exception) {
            // 可能缺权限 (Android 14+ 前台服务需声明类型), 忽略
        }
    }
}