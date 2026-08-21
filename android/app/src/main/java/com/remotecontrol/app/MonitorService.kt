package com.remotecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * v2.0 - 前台监控服务
 * 职责:
 *   1. 后台常驻: 保持进程存活 + 前台通知(可关闭)
 *   2. 创建通知渠道: 监控告警 / 后台运行
 *   3. 接收服务端主动推送(push.*) → 发本地通知
 *   4. 定时关机下发/取消 (通过 WsClient)
 */
class MonitorService : Service() {

    companion object {
        const val TAG = "MonitorService"
        const val CHANNEL_FOREGROUND = "rc_foreground"
        const val CHANNEL_ALERT = "rc_alert"
        const val NOTIF_FOREGROUND_ID = 1001
        const val ACTION_STOP = "com.remotecontrol.app.STOP_BACKGROUND"
        const val PREFS = "rc_settings"

        @Volatile var running = false
            private set

        fun isRunning(): Boolean = running
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pushListener: (JSONObject) -> Unit = { msg ->
        val type = msg.optString("type")
        val text = msg.optString("text")
        if (type.startsWith("push.") && text.isNotBlank()) {
            pushAlert("远程控制", text)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createChannels()
        RemoteControlApp.ws.pushListener = null
        RemoteControlApp.ws.pushListener = pushListener // 注册推送监听
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "用户关闭后台常驻")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        return START_STICKY // 被杀后尝试重启
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        running = false
        if (RemoteControlApp.ws.pushListener === pushListener) {
            RemoteControlApp.ws.pushListener = null
        }
        scope.cancel()
    }

    /** 启动前台(常驻)通知 */
    fun startForegroundCompat() {
        running = true
        startForeground(NOTIF_FOREGROUND_ID, buildForegroundNotification())
    }

    private fun buildForegroundNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 停止后台按钮
        val stopIntent = Intent(this, MonitorService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 点击打开 App
        val openApp = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 1, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // 后台常驻开关
        val keepRun = prefs().getBoolean("background_enabled", true)

        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setContentTitle(getString(R.string.notif_foreground_title))
            .setContentText(getString(R.string.notif_foreground_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .setOngoing(keepRun)   // 若后台常驻开启则不可滑掉; 关闭则可滑掉
            .addAction(R.mipmap.ic_launcher, getString(R.string.notif_foreground_stop), stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 发监控告警通知 */
    fun pushAlert(title: String, content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "无通知权限, 跳过推送: $content")
            return
        }
        val openApp = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, (System.currentTimeMillis() % 100000).toInt() + 1, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify((System.currentTimeMillis() % 10000).toInt() + 1000, notif)
        } catch (e: Exception) {
            Log.w(TAG, "通知失败: " + e.message)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val fg = NotificationChannel(
                CHANNEL_FOREGROUND, getString(R.string.channel_foreground),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_foreground_text) }
            val alert = NotificationChannel(
                CHANNEL_ALERT, getString(R.string.channel_monitor),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.settings_alert_switch_sum) }
            nm.createNotificationChannel(fg)
            nm.createNotificationChannel(alert)
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}