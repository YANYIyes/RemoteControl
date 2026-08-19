package com.remotecontrol.app

import android.content.SharedPreferences
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("rc_settings", MODE_PRIVATE)
        ws.listener = this

        // 恢复服务器地址
        val savedUrl = prefs.getString("server_url", "wss://YOUR_SERVER:8899") ?: ""
        binding.etServer.setText(savedUrl)
        binding.tvSerial.text = "设备序列号: ${ws.deviceSerial()}"
        binding.tvDevice.text = "${android.os.Build.MODEL} / ${android.os.Build.MANUFACTURER}   v${BuildConfig.VERSION_NAME}"

        setupProcessList()
        setupActions()
        updateConnectionUI(false)
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
        binding.btnRefresh.setOnClickListener { loadProcesses(binding.etSearch.text.toString().trim()) }
        binding.btnSystemInfo.setOnClickListener { startActivity(Intent(this, SystemInfoActivity::class.java)) }
        binding.btnFiles.setOnClickListener { startActivity(Intent(this, FileBrowserActivity::class.java)) }
        binding.btnScreen.setOnClickListener { startActivity(Intent(this, ScreenActivity::class.java)) }
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
