package com.remotecontrol.app

import android.app.Activity

/**
 * v2.0 - 主题工具: 按设置应用配色
 * 每个 Activity 在 onCreate 第一行调用 AppTheme.apply(this)
 */
object AppTheme {

    /** 当前主题名: blue|purple|green|orange|night */
    fun currentName(activity: Activity): String {
        val prefs = activity.getSharedPreferences(MonitorService.PREFS, android.content.Context.MODE_PRIVATE)
        return prefs.getString("theme", "blue") ?: "blue"
    }

    /** 在 setContentView 之前调用, 应用配色主题 */
    fun apply(activity: Activity) {
        val name = currentName(activity)
        val id = when (name) {
            "purple" -> R.style.Theme_RemoteControl_Purple
            "green" -> R.style.Theme_RemoteControl_Green
            "orange" -> R.style.Theme_RemoteControl_Orange
            "night" -> R.style.Theme_RemoteControl_Night
            else -> R.style.Theme_RemoteControl
        }
        activity.setTheme(id)
    }
}