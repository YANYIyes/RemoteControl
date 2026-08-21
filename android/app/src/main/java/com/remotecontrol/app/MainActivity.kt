package com.remotecontrol.app

import android.Manifest
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.remotecontrol.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity(), WsClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val ws = RemoteControlApp.ws

    private val processAdapter = ProcessAdapter { pid ->
        confirmKill(pid)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.apply(this) // v2.0: 应用配色
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("rc_settings", MODE_PRIVATE)
        ws.listener = this

        // v2.0: 恢复服务器地址 (受"记住服务器"开关控制)
        val remember = prefs.getBoolean("remember_server", true)
        val savedUrl = if (remember) (prefs.getString("server_url", "wss://remote.641188.xyz") ?: "wss://remote.641188.xyz") else "wss://remote.641188.xyz"
        binding.etServer.setText(savedUrl)
        binding.tvSerial.text = "设备序列号: ${ws.deviceSerial()}"
        binding.tvDevice.text = "${android.os.Build.MODEL} / ${android.os.Build.MANUFACTURER}   v${BuildConfig.VERSION_NAME}"

        setupProcessList()
        setupActions()
        updateConnectionUI(false)
        requestNotificationPermission() // v2.0: Android 13+ 首次请求通知权限
    }

    override fun onDestroy() {
        super.onDestroy()
        // 全局连接由 App 进程管理, 不能在此断开 (否则切到子页面/旋转屏幕会断联)
        if (ws.listener === this) ws.listener = null
    }

    override fun onResume() {
        super.onResume()
        // 回到本页时确保 listener 指向本页
        ws.listener = this
    }

    private fun setupProcessList() {
        binding.rvProcess.layoutManager = LinearLayoutManager(this)
        binding.rvProcess.adapter = processAdapter
        binding.btnSearch.setOnClickListener {
            val q = binding.etSearch.text.toString().trim()
            loadProcesses(q)
        }
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            loadProcesses(binding.etSearch.text.toString().trim())
            true
        }
    }

    private fun setupActions() {
        binding.btnConnect.setOnClickListener {
            val url = binding.etServer.text.toString().trim()
            if (url.isEmpty()) {
                showToast("请输入服务器地址")
                return@setOnClickListener
            }
            prefs.edit().putString("server_url", url).apply()
            ws.connect(url)
        }
        binding.btnShutdown.setOnClickListener {
            confirmSystem("确定要远程关闭电脑吗？") { ws.shutdown() }
        }
        binding.btnReboot.setOnClickListener {
            confirmSystem("确定要远程重启电脑吗？") { ws.reboot() }
        }
        // v2.0: 定时关机
        binding.btnSchedule.setOnClickListener { showScheduleDialog() }
        binding.btnRefresh.setOnClickListener { loadProcesses(binding.etSearch.text.toString().trim()) }
        binding.btnSystemInfo.setOnClickListener { startActivity(Intent(this, SystemInfoActivity::class.java)) }
        binding.btnFiles.setOnClickListener { startActivity(Intent(this, FileBrowserActivity::class.java)) }
        binding.btnScreen.setOnClickListener { startActivity(Intent(this, ScreenActivity::class.java)) }
        // v2.0: 设置入口
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    // v2.0: Android 13+ 请求通知权限 (首次启动)
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun confirmSystem(msg: String, action: suspend () -> JSONObject) {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认操作")
            .setMessage(msg)
            .setPositiveButton("确认") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val r = action()
                        handleResult(r)
                    } catch (e: Exception) {
                        showToast(e.message ?: "操作失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // v2.0: 定时关机对话框 (HH:MM 每天) + 取消
    private fun showScheduleDialog() {
        val items = arrayOf("设定每天定时关机", "取消定时关机")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.schedule_dialog_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickScheduleTime()
                    1 -> lifecycleScope.launch {
                        try {
                            val r = ws.cancelSchedule()
                            showToast(if (r.optBoolean("ok", false)) "已取消定时关机" else (r.optString("error", "取消失败")))
                        } catch (e: Exception) { showToast(e.message ?: "取消失败") }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // 时间输入 (HH:MM 每天) → 设定定时关机
    private fun pickScheduleTime() {
        val et = EditText(this).apply {
            hint = "如 23:00 (每天)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.schedule_daily))
            .setView(et)
            .setPositiveButton(getString(R.string.schedule_set)) { _, _ ->
                val time = et.text.toString().trim()
                if (time.isEmpty()) { showToast("请输入时间"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        val r = ws.addSchedule(time)
                        showToast(if (r.optBoolean("ok", false)) r.optString("message", "已设定") else (r.optString("error", "设定失败")))
                    } catch (e: Exception) { showToast(e.message ?: "设定失败") }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun confirmKill(pid: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("结束进程")
            .setMessage("确定要结束进程 PID=$pid 吗？")
            .setPositiveButton("结束") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val r = ws.killProcess(pid)
                        handleResult(r)
                        loadProcesses(binding.etSearch.text.toString().trim())
                    } catch (e: Exception) {
                        showToast(e.message ?: "操作失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleResult(r: JSONObject) {
        val type = r.optString("type")
        val ok = r.optBoolean("ok", false)
        val error = r.optString("error", "")
        when {
            ok -> showToast("操作成功")
            else -> showToast("操作失败: ${error.ifBlank { type }}")
        }
    }

    private fun loadProcesses(search: String?) {
        lifecycleScope.launch {
            try {
                val r = ws.listProcesses(search)
                // 服务端 process.list 成功返回 { processes:[...] } (无 ok 字段), 失败返回 { error } 
                if (r.has("processes")) {
                    val arr = r.optJSONArray("processes") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                    processAdapter.submit(list)
                    binding.tvCount.text = "共 ${list.size} 个进程"
                } else {
                    processAdapter.submit(emptyList())
                    showToast(r.optString("error", "获取进程失败"))
                }
            } catch (e: Exception) {
                processAdapter.submit(emptyList())
                showToast(e.message ?: "获取进程失败")
            }
        }
    }

    private fun updateConnectionUI(connected: Boolean) {
        binding.btnConnect.isEnabled = !connected
        binding.etServer.isEnabled = !connected
        binding.cardActions.isEnabled = connected
        binding.cardProcess.isEnabled = connected
        binding.tvStatus.text = if (connected) "● 已连接" else "○ 未连接"
        binding.btnRefresh.isEnabled = connected
        binding.btnSearch.isEnabled = connected
    }

    override fun onStatus(status: String, detail: String) {
        runOnUiThread {
            binding.tvStatus.text = when (status) {
                "connected" -> "● 已连接"
                "connecting" -> "◌ 连接中…"
                "disconnected" -> "○ 未连接"
                "pending_auth" -> "◌ 待授权: $detail"
                "rejected" -> "✕ 被拒绝: $detail"
                "error" -> "○ 错误: $detail"
                else -> binding.tvStatus.text
            }
            if (status == "connected") {
                updateConnectionUI(true)
                loadProcesses(null)
            } else {
                updateConnectionUI(false)
            }
        }
    }

    override fun onAuthResult(ok: Boolean, pending: Boolean, error: String) {
        if (pending) {
            runOnUiThread { showToast("设备待授权，请在电脑或QQ确认绑定") }
        }
    }

    override fun onResponse(cmd: String, payload: JSONObject) {
        // handled via request/await
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSnack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }
}
