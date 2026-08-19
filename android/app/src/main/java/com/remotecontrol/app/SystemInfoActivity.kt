package com.remotecontrol.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

class SystemInfoActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private val ws = RemoteControlApp.ws

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sysinfo)

        tvInfo = findViewById(R.id.tvSysInfo)
        val btnRefresh = findViewById<Button>(R.id.btnSysRefresh)
        btnRefresh.setOnClickListener { load() }
        load()
    }

    private fun load() {
        if (!ws.connected) { toast("未连接"); return }
        tvInfo.text = "加载中…"
        lifecycleScope.launch {
            try {
                val r = ws.systemInfo()
                if (r.optBoolean("ok", false)) { render(r); }
                else toast(r.optString("error", "获取失败"))
            } catch (e: Exception) { toast(e.message ?: "失败") }
        }
    }

    private fun render(r: org.json.JSONObject) {
        val up = r.optJSONObject("upTime") ?: org.json.JSONObject()
        val memTotal = r.optDouble("totalMem", 0.0)
        val memFree = r.optDouble("freeMem", 0.0)
        val memUsed = memTotal - memFree
        val sb = StringBuilder()
        sb.append("🖥 操作系统\n  ").append(r.optString("osName", "?")).append('\n')
        sb.append("🏷 主机名\n  ").append(r.optString("hostName", "?")).append('\n')
        sb.append("⚙️ CPU\n  ").append(r.optString("cpuName", "?")).append('\n')
        sb.append("📊 CPU负载\n  ").append(r.optDouble("cpuLoad", 0.0)).append("%\n")
        sb.append("⏱ 开机时长\n  ").append(up.optInt("days", 0)).append("天 ")
            .append(up.optInt("hours", 0)).append("小时 ").append(up.optInt("mins", 0)).append("分\n")
        sb.append("💾 内存占用\n  ").append(fmt(memUsed)).append(" / ").append(fmt(memTotal))
            .append("  (已用 ").append(if (memTotal > 0) ((memUsed / memTotal * 100).toInt()) else 0).append("%)\n")
        val disks = r.optJSONArray("disks")
        if (disks != null && disks.length() > 0) {
            sb.append("💽 磁盘\n")
            for (i in 0 until disks.length()) {
                val d = disks.getJSONObject(i)
                sb.append("  ").append(d.optString("drive", "?")).append("  已用 ")
                    .append(fmt(d.optDouble("total", 0.0) - d.optDouble("free", 0.0)))
                    .append(" / ").append(fmt(d.optDouble("total", 0.0))).append('\n')
            }
        }
        tvInfo.text = sb.toString().trimEnd()
    }

    private fun fmt(bytes: Double): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
}
