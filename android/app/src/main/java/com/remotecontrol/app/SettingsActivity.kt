package com.remotecontrol.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.remotecontrol.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v2.0 - 设置界面
 * 主题配色切换 / 记住服务器 / 通知权限 / 后台常驻 / 告警开关
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences(MonitorService.PREFS, MODE_PRIVATE) }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        binding.swNotify.isChecked = granted
        prefs.edit().putBoolean("notify_enabled", granted).apply()
        if (!granted) Toast.makeText(this, "通知权限未授予, 将无法接收告警", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectTheme() // 应用配色
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBack()
        setupRememberServer()
        setupTheme()
        setupNotify()
        setupBackground()
        setupAlerts()
    }

    /** 按设置应用主题 (供其它 Activity 复用) */
    fun selectTheme() {
        val theme = prefs.getString("theme", "blue")
        val id = when (theme) {
            "purple" -> R.style.Theme_RemoteControl_Purple
            "green" -> R.style.Theme_RemoteControl_Green
            "orange" -> R.style.Theme_RemoteControl_Orange
            "night" -> R.style.Theme_RemoteControl_Night
            else -> R.style.Theme_RemoteControl
        }
        setTheme(id)
    }

    private fun setupBack() {
        binding.btnSettingsBack.setOnClickListener { finish() }
    }

    private fun setupRememberServer() {
        binding.swRememberServer.isChecked = prefs.getBoolean("remember_server", true)
        binding.swRememberServer.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("remember_server", checked).apply()
        }
    }

    private fun setupTheme() {
        binding.btnThemeBlue.setOnClickListener { applyTheme("blue") }
        binding.btnThemePurple.setOnClickListener { applyTheme("purple") }
        binding.btnThemeGreen.setOnClickListener { applyTheme("green") }
        binding.btnThemeOrange.setOnClickListener { applyTheme("orange") }
        binding.btnThemeNight.setOnClickListener { applyTheme("night") }
    }

    private fun applyTheme(name: String) {
        prefs.edit().putString("theme", name).apply()
        // 重新应用主题并重启界面
        recreate()
    }

    private fun setupNotify() {
        val hasNotif = hasNotificationPermission()
        binding.swNotify.isChecked = hasNotif
        binding.swNotify.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                requestNotifyPermission()
            } else {
                // 通知权限无法程序化关闭 (系统级), 仅提示
                Toast.makeText(this, "通知权限由系统管理, 请在系统设置中关闭", Toast.LENGTH_SHORT).show()
                binding.swNotify.isChecked = true // 保持
            }
        }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
        } else {
            true // 低版本无需运行时权限
        }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupBackground() {
        binding.swBackground.isChecked = prefs.getBoolean("background_enabled", true)
        binding.swBackground.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("background_enabled", checked).apply()
            if (checked) {
                (application as RemoteControlApp).startMonitorService()
                Toast.makeText(this, "后台常驻已开启", Toast.LENGTH_SHORT).show()
            } else {
                (application as RemoteControlApp).stopMonitorService()
                Toast.makeText(this, "后台常驻已关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAlerts() {
        // 告警总开关
        binding.swAlert.isChecked = prefs.getBoolean("alert_enabled", true)
        binding.swAlert.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("alert_enabled", checked).apply()
            pushConfig()
        }
        // CPU 告警
        binding.swCpuAlert.isChecked = prefs.getBoolean("cpu_alert_enabled", true)
        binding.swCpuAlert.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("cpu_alert_enabled", checked).apply()
            pushConfig()
        }
        // 进程消失告警
        binding.swProcAlert.isChecked = prefs.getBoolean("proc_alert_enabled", true)
        binding.swProcAlert.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("proc_alert_enabled", checked).apply()
            pushConfig()
        }
    }

    /** 把告警配置同步到服务端 (push.setConfig) */
    private fun pushConfig() {
        val cfg = JSONObject().apply {
            put("notifyEnabled", prefs.getBoolean("alert_enabled", true))
            put("cpuNotify", prefs.getBoolean("cpu_alert_enabled", true))
            put("procNotify", prefs.getBoolean("proc_alert_enabled", true))
            put("cpuThreshold", 90)
            put("monitored", org.json.JSONArray())
        }
        if (RemoteControlApp.ws.connected) {
            // 协程异步下发 (不阻塞 UI)
            lifecycleScope.launch(Dispatchers.IO) {
                try { RemoteControlApp.ws.request("push.setConfig", JSONObject().put("config", cfg)) } catch (_: Exception) {}
            }
        }
    }
}